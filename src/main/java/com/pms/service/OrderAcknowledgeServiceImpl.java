package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.repository.OrderLineRepository;
import com.pms.service.OrderAcknowledgeAdapter.AcknowledgeOutcome;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.reservation.InternalShipmentStageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link OrderAcknowledgeService} implementation — the order-confirmation leg; each marketplace sends through its
 * {@link OrderAcknowledgeAdapter} (FEATURE_2610_23 / D21).
 *
 * Flow: dedupe line ids → expand with {@code findWithAccountByIdIn} → classify lines (no adapter · no box =
 * unsupported, not paid = skipped) → each marketplace's adapter sends its paid lines in the platform order (Coupang →
 * 11st) and writes PREPARING back to what succeeded (PLAN 2609_17 D3) → the internal-stage steps run only for a
 * marketplace whose adapter opens the internal stage (FEATURE_2610_23 / D23 · D24).
 *
 * ⚠️ Send failures are isolated inside each adapter — only those units go to failed and the rest are still sent.
 * ⚠️ Never put {@code @Transactional} on this service — it runs external HTTP.
 *    {@code OrderLine} is read only through {@code @EntityGraph} finders (open-in-view=false).
 * ⚠️ Order confirmation cannot be undone. There are only three callers — the controller (the screen's [발주처리]
 *    button) · <b>the run of a reservation the user made</b> (FEATURE_2609_75 / D6 — partly reverses 2609_17 D4) ·
 *    the internal-stage branch of [지금 발송] (D27). All three are paths where the user picked the orders.
 *    The order sync and other schedules still never call it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAcknowledgeServiceImpl implements OrderAcknowledgeService {

    /**
     * 전송 대상 상태 — 결제완료(PAID)만.
     * ⚠️ 발송처리(PLAN 2609_07 D1)의 블랙리스트와 <b>방향이 반대</b>다. 거기는 "모르는 상태면 보낸다"
     *    (안 보내면 발송 누락), 여기는 "모르는 상태면 안 보낸다"(잘못 보내면 되돌릴 수 없다).
     *    상태는 되돌아오지 않으므로(단조성) 스킵으로 누락될 주문이 구조적으로 없다. PLAN 2609_17 D2.
     */
    private static final OrderStatus STATUS_PAID = OrderStatus.PAID;

    private final OrderLineRepository orderLineRepository;
    private final InternalShipmentStageService internalShipmentStageService;
    /** FEATURE_2610_23 / D21: one confirmation sender per marketplace. */
    private final List<OrderAcknowledgeAdapter> acknowledgeAdapters;

    @Override
    public OrderAcknowledgeResult acknowledge(OrderAcknowledgeRequest request) {
        List<Long> ids = request.orderItemIds().stream().distinct().toList();
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        return send(lines, true).result();
    }

    @Override
    public ReservationAckResult acknowledgeForReservation(List<Long> orderLineIds) {
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderLineIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            return new ReservationAckResult(List.of(), List.of());
        }
        Outcome outcome = send(lines, false);
        return new ReservationAckResult(outcome.succeededBoxIds(), outcome.result().failed());
    }

    /**
     * Classify the lines → each marketplace's adapter sends → internal-stage release.
     *
     * @param manual true = the screen button path. A running reservation bundle mixed in sends nothing and is a 400
     *               (D18); the succeeded bundles' internal stage and reservation results are released (D14).
     *               false = the reservation run path (02).
     */
    private Outcome send(List<OrderLine> lines, boolean manual) {
        List<String> unsupported = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        // skipped·unsupported 는 주문번호 단위다 — 옵션 3줄짜리 주문이 결과에 3번 나오지 않게 한 번만 담는다.
        // (status 는 첫 라인의 것 — ShipmentConfirmResult.SkippedOrder 와 같은 계약)
        Set<String> reportedOrders = new LinkedHashSet<>();
        // FEATURE_2610_23 / D21: paid lines per marketplace — EnumMap keeps the platform order (Coupang → 11st).
        Map<Platform, List<OrderLine>> paidByPlatform = new EnumMap<>(Platform.class);

        for (OrderLine line : lines) {
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            String orderId = line.getOrder().getExternalOrderId();
            String boxId = (line.getOrderShipment() == null)
                    ? null : line.getOrderShipment().getExternalShipmentId();
            // Platform judged by the account — the same basis as ShipmentConfirmServiceImpl so the two legs never
            // diverge (orders has a platform column too, but it is not used). A marketplace without an adapter is
            // unsupported (FEATURE_2610_23 / D14).
            if (adapterFor(account.getPlatform()).isEmpty() || boxId == null || boxId.isBlank()) {
                if (reportedOrders.add(orderId)) {
                    unsupported.add(orderId);
                }
                continue;
            }
            if (line.getStatus() != STATUS_PAID) {
                if (reportedOrders.add(orderId)) {
                    // D47: the acknowledge screen has no reason column → null.
                    skipped.add(new SkippedOrder(orderId,
                            line.getStatus() == null ? null : line.getStatus().name(), null));
                }
                continue;
            }
            paidByPlatform.computeIfAbsent(account.getPlatform(), k -> new ArrayList<>()).add(line);
        }

        if (manual) {
            // 처리 중인 예약 발송 묶음이 섞였으면 한 건도 보내지 않는다(D18 「처리 중」).
            internalShipmentStageService.assertNotRunning(internalShipmentIds(paidByPlatform));
        }

        int targetBoxes = 0;
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        List<String> succeededBoxIds = new ArrayList<>();
        // FEATURE_2610_17 / D57: the per-marketplace split — only marketplaces that had something to send.
        List<OrderAcknowledgeResult.PlatformResult> byPlatform = new ArrayList<>();

        for (Map.Entry<Platform, List<OrderLine>> entry : paidByPlatform.entrySet()) {
            OrderAcknowledgeAdapter adapter = adapterFor(entry.getKey()).orElseThrow();
            AcknowledgeOutcome outcome = adapter.acknowledge(entry.getValue());
            if (adapter.internalStage()) {
                succeededBoxIds.addAll(outcome.succeededBoxIds());
                if (manual) {
                    releaseInternalStage(outcome.succeededBoxIds(), linesByBoxId(entry.getValue()));
                }
            }
            outcome.skipped().stream()
                    .filter(r -> skipped.stream().noneMatch(s -> s.orderId().equals(r.orderId())))
                    .forEach(skipped::add);
            targetBoxes += outcome.targets();
            succeeded += outcome.succeeded();
            failed.addAll(outcome.failed());
            if (outcome.targets() > 0) {
                byPlatform.add(new OrderAcknowledgeResult.PlatformResult(entry.getKey(), outcome.succeeded(),
                        outcome.failed()));
            }
        }

        log.info("발주처리 결과: lines={} boxes={} succeeded={} failed={} skipped={} unsupported={}",
                lines.size(), targetBoxes, succeeded, failed.size(), skipped.size(), unsupported.size());

        return new Outcome(new OrderAcknowledgeResult(lines.size(), targetBoxes, succeeded, failed, skipped,
                unsupported, List.copyOf(byPlatform)), List.copyOf(succeededBoxIds));
    }

    /** The adapter whose platform matches — same lookup as {@code ClaimActionServiceImpl.resolve()}. */
    private Optional<OrderAcknowledgeAdapter> adapterFor(Platform platform) {
        return acknowledgeAdapters.stream().filter(adapter -> adapter.platform() == platform).findFirst();
    }

    /** Delivery bundle ids of the paid lines whose marketplace opens the internal stage (D23 · D24). */
    private Set<Long> internalShipmentIds(Map<Platform, List<OrderLine>> paidByPlatform) {
        Set<Long> shipmentIds = new LinkedHashSet<>();
        paidByPlatform.forEach((platform, platformLines) -> {
            if (adapterFor(platform).map(OrderAcknowledgeAdapter::internalStage).orElse(false)) {
                shipmentIds.addAll(shipmentIdsOf(linesByBoxId(platformLines).keySet(),
                        linesByBoxId(platformLines)));
            }
        });
        return shipmentIds;
    }

    /** Box id → its lines, in line order. */
    private static Map<String, List<OrderLine>> linesByBoxId(List<OrderLine> lines) {
        Map<String, List<OrderLine>> byBox = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            byBox.computeIfAbsent(line.getOrderShipment().getExternalShipmentId(), k -> new ArrayList<>()).add(line);
        }
        return byBox;
    }

    /** 박스 id → 그 박스 라인들의 배송 묶음 id. id 가 없는 묶음(저장 전)은 건너뛴다. */
    private Set<Long> shipmentIdsOf(Collection<String> boxIds, Map<String, List<OrderLine>> linesByBoxId) {
        Set<Long> shipmentIds = new LinkedHashSet<>();
        for (String boxId : boxIds) {
            for (OrderLine line : linesByBoxId.getOrDefault(boxId, List.of())) {
                if (line.getOrderShipment() != null && line.getOrderShipment().getId() != null) {
                    shipmentIds.add(line.getOrderShipment().getId());
                }
            }
        }
        return shipmentIds;
    }

    /**
     * Releases the internal stage and the reservation results of the boxes a manual confirmation succeeded for (D14).
     * ⚠️ Never throws — the marketplace already accepted the confirmation (the same reason as the adapter's
     *    {@code PREPARING} write-back).
     */
    private void releaseInternalStage(List<String> succeededBoxIds, Map<String, List<OrderLine>> linesByBoxId) {
        try {
            Set<Long> shipmentIds = shipmentIdsOf(succeededBoxIds, linesByBoxId);
            if (!shipmentIds.isEmpty()) {
                internalShipmentStageService.clearAfterManualAcknowledge(shipmentIds);
            }
        } catch (Exception e) {
            log.warn("발주처리 후 내부 단계·예약 해제 실패 (쿠팡 전송은 성공): {}", e.getMessage());
        }
    }

    /** {@link #send} 결과 — 화면 응답 + 성공 박스 id(예약 실행 경로가 쓴다, 02). */
    private record Outcome(OrderAcknowledgeResult result, List<String> succeededBoxIds) {
    }
}
