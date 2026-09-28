package com.pms.service.reservation;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link InternalShipmentStageService} 구현 (FEATURE_2609_75 / D1 · D14 · D18).
 *
 * <p>🔴 단계 변경은 {@code OrderShipmentRepository.setInternalStage}/{@code clearInternalStage} 로만 한다 —
 * 엔티티 칸이 {@code updatable = false} 라 {@code toBuilder()} 저장으로는 바뀌지 않는다(PLAN §4-1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalShipmentStageServiceImpl implements InternalShipmentStageService {

    public static final String RUNNING_MESSAGE =
            "예약 발송이 처리 중인 주문이 있습니다. 처리가 끝난 뒤 다시 시도하세요";
    static final String RELEASED_BY_ACKNOWLEDGE =
            "발주처리 버튼으로 쿠팡에 발주처리되어 예약을 해제했습니다";
    static final String RELEASED_BY_SHIP_NOW =
            "지금 발송으로 쿠팡에 송장이 등록되어 예약을 해제했습니다";
    static final String RELEASED_BY_INTERNAL_RELEASE =
            "내부 발주를 해제해 저장된 송장을 닫았습니다";

    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ReservedShipmentItemRepository reservedShipmentItemRepository;
    private final ReservedShipmentSettler reservedShipmentSettler;

    @Override
    @Transactional
    public InternalStageResult markInternal(List<Long> orderItemIds) {
        return change(orderItemIds, null, InternalShipmentStage.INTERNAL_PREPARING, true);
    }

    @Override
    @Transactional
    public InternalStageResult releaseInternal(List<Long> orderItemIds) {
        return change(orderItemIds, InternalShipmentStage.INTERNAL_PREPARING, null, false);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertNotRunning(Collection<Long> orderShipmentIds) {
        if (orderShipmentIds.isEmpty()) {
            return;
        }
        if (reservedShipmentItemRepository.existsByOrderShipment_IdInAndReservedShipment_Status(
                orderShipmentIds, ReservedShipmentStatus.RUNNING)) {
            throw new IllegalArgumentException(RUNNING_MESSAGE);
        }
    }

    @Override
    @Transactional
    public void clearAfterManualAcknowledge(Collection<Long> orderShipmentIds) {
        clearAndRelease(orderShipmentIds, RELEASED_BY_ACKNOWLEDGE);
    }

    @Override
    @Transactional
    public void clearAfterShipNow(Collection<Long> orderShipmentIds) {
        clearAndRelease(orderShipmentIds, RELEASED_BY_SHIP_NOW);
    }

    @Override
    @Transactional
    public void releasePartialCancel(Collection<Long> orderShipmentIds) {
        if (orderShipmentIds.isEmpty()) {
            return;
        }
        List<ReservedShipmentItem> open = reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(
                orderShipmentIds,
                List.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED, ReservedItemResult.STORED));
        Set<Long> reservationIds = new LinkedHashSet<>();
        for (ReservedShipmentItem item : open) {
            reservedShipmentItemRepository.save(item.toBuilder()
                    .result(ReservedItemResult.RELEASED)
                    .failureReason(ReservedShipmentExecutor.PARTIAL_CANCEL_MESSAGE)
                    .build());
            if (item.getReservedShipment() != null) {        // STORED = no reservation (D18)
                reservationIds.add(item.getReservedShipment().getId());
            }
        }
        orderShipmentRepository.setInternalStage(orderShipmentIds, InternalShipmentStage.INTERNAL_PREPARING.name());
        reservedShipmentSettler.settle(reservationIds);
        log.info("일부 취소 해제([지금 발송]): shipments={} releasedItems={}", orderShipmentIds.size(), open.size());
    }

    /**
     * 내부 단계를 지우고 남은(PENDING·FAILED) 예약 결과와 보관 송장(STORED, D18)을 RELEASED 로 — {@code reason} 은 호출 경로별 문구.
     * STORED 행은 예약이 없다(null) — 닫을 예약 목록에 넣지 않는다.
     */
    private void clearAndRelease(Collection<Long> orderShipmentIds, String reason) {
        if (orderShipmentIds.isEmpty()) {
            return;
        }
        List<ReservedShipmentItem> open = reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(
                orderShipmentIds,
                List.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED, ReservedItemResult.STORED));
        Set<Long> reservationIds = new LinkedHashSet<>();
        for (ReservedShipmentItem item : open) {
            reservedShipmentItemRepository.save(item.toBuilder()
                    .result(ReservedItemResult.RELEASED)
                    .failureReason(reason)
                    .build());
            if (item.getReservedShipment() != null) {
                reservationIds.add(item.getReservedShipment().getId());
            }
        }
        orderShipmentRepository.clearInternalStage(orderShipmentIds);
        reservedShipmentSettler.settle(reservationIds);
        log.info("내부 단계 해제: reason={} shipments={} releasedItems={}", reason, orderShipmentIds.size(), open.size());
    }

    /**
     * 라인 → 배송 묶음으로 묶어 {@code from} 단계인 묶음만 {@code to} 로 바꾼다.
     *
     * @param requirePaid true 면 그 묶음에 결제완료이면서 전량취소가 아닌 라인이 1개 이상 있어야 한다(내부 발주)
     */
    private InternalStageResult change(List<Long> orderItemIds, InternalShipmentStage from,
                                       InternalShipmentStage to, boolean requirePaid) {
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderItemIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        List<String> unsupported = new ArrayList<>();
        Map<Long, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            String orderId = line.getOrder().getExternalOrderId();
            OrderShipment shipment = line.getOrderShipment();
            if (!Platform.COUPANG.equals(line.getOrder().getMarketplaceAccount().getPlatform()) || shipment == null) {
                if (!unsupported.contains(orderId)) {
                    unsupported.add(orderId);
                }
                continue;
            }
            linesByShipment.computeIfAbsent(shipment.getId(), k -> new ArrayList<>()).add(line);
        }

        List<Long> targets = new ArrayList<>();
        Set<String> changedOrders = new LinkedHashSet<>();
        Set<String> skippedOrders = new LinkedHashSet<>();
        for (Map.Entry<Long, List<OrderLine>> entry : linesByShipment.entrySet()) {
            List<OrderLine> shipmentLines = entry.getValue();
            OrderShipment shipment = shipmentLines.get(0).getOrderShipment();
            String orderId = shipmentLines.get(0).getOrder().getExternalOrderId();
            boolean eligible = shipment.getInternalStage() == from
                    && (!requirePaid || shipmentLines.stream()
                            .anyMatch(l -> l.getStatus() == OrderStatus.PAID && !l.isFullyCancelled()));
            if (eligible) {
                targets.add(entry.getKey());
                changedOrders.add(orderId);
            } else {
                skippedOrders.add(orderId);
            }
        }
        skippedOrders.removeAll(changedOrders);

        if (!targets.isEmpty()) {
            if (to == null) {
                clearAndRelease(targets, RELEASED_BY_INTERNAL_RELEASE);   // D18 — E2 해제는 보관 송장(STORED)도 닫는다
            } else {
                orderShipmentRepository.setInternalStage(targets, to.name());
            }
        }
        log.info("내부 단계 변경: {} -> {} lines={} shipments={} skipped={} unsupported={}",
                from, to, lines.size(), targets.size(), skippedOrders.size(), unsupported.size());
        return new InternalStageResult(lines.size(), targets.size(), List.copyOf(skippedOrders), unsupported);
    }
}
