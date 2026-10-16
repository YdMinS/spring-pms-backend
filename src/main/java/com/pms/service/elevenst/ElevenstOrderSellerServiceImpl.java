package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ElevenstDeliveryDelayRequest;
import com.pms.dto.request.ElevenstVisitPickupRequest;
import com.pms.dto.response.ElevenstChoicesResponse;
import com.pms.dto.response.ElevenstDeliveryLineResponse;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link ElevenstOrderSellerService} implementation (FEATURE_2610_17 / D63 · D64 · D66 · D67).
 *
 * <p>⚠️ No {@code @Transactional}: 11st HTTP runs here; the local write after a success is one repository save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstOrderSellerServiceImpl implements ElevenstOrderSellerService {

    static final String NOT_ELEVENST_MESSAGE = "11번가 주문이 아닙니다";
    static final String DELAY_STATUS_MESSAGE = "결제완료 · 배송준비중 주문만 발송지연 안내를 보낼 수 있습니다";
    static final String DELAY_DATE_MESSAGE = "예상 발송일은 내일부터 30일 뒤까지 고를 수 있습니다";
    /** D63: 11st −28307 「상세사유 값은 20~300Byte」, shown in characters. */
    static final String DELAY_TEXT_MESSAGE = "상세 사유는 한글 10~150자로 적어 주세요.";
    static final String VISIT_MESSAGE = "결제완료 · 배송준비중 방문수령 주문만 완료할 수 있습니다";
    static final String STOCK_MESSAGE = "예약판매 입고 대기 주문이 아닙니다";
    static final String STOCK_SENT_MESSAGE = "이미 입고완료 처리를 보냈습니다";
    private static final int DELAY_MAX_DAYS = 30;
    private static final int DELAY_TEXT_MIN_BYTES = 20;
    private static final int DELAY_TEXT_MAX_BYTES = 300;
    private static final Set<OrderStatus> OPEN_STATUSES = Set.of(OrderStatus.PAID, OrderStatus.PREPARING);

    private final OrderLineRepository orderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final ElevenstSellerActions elevenstSellerActions;

    @Override
    public ElevenstChoicesResponse choices() {
        return new ElevenstChoicesResponse(ElevenstSellerChoices.SELLER_CANCEL_REASONS,
                ElevenstSellerChoices.FAULT_REJECT_REASONS, ElevenstSellerChoices.DELAY_REASONS);
    }

    @Override
    public List<ElevenstDeliveryLineResponse> deliveryLines(Long orderItemId) {
        Target target = target(orderItemId);
        String dlvNo = target.dlvNo();
        List<OrderLine> lines = orderLineRepository.findByExternalOrderId(target.line().getOrder().getExternalOrderId())
                .stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(target.account().getId()))
                .filter(l -> l.getOrderShipment() != null && dlvNo.equals(l.getOrderShipment().getExternalShipmentId()))
                .toList();
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        return lines.stream()
                .map(l -> new ElevenstDeliveryLineResponse(l.getId(), l.getItemName(), l.getOrderQty(),
                        l.getStatus() == null ? null : l.getStatus().name(),
                        ElevenstRawFields.yes(mirrors.get(l.getId()), "addPrdYn")))
                .toList();
    }

    @Override
    public void deliveryDelay(Long orderItemId, ElevenstDeliveryDelayRequest request) {
        Target target = target(orderItemId);
        if (!OPEN_STATUSES.contains(target.line().getStatus())) {
            throw new IllegalArgumentException(DELAY_STATUS_MESSAGE);
        }
        LocalDate today = LocalDate.now(SyncWindow.KST);
        LocalDate expected = request.expectedDate();
        if (!expected.isAfter(today) || expected.isAfter(today.plusDays(DELAY_MAX_DAYS))) {
            throw new IllegalArgumentException(DELAY_DATE_MESSAGE);
        }
        String reasonCode = request.reasonCode().trim();
        ElevenstSellerChoices.requireLabel(ElevenstSellerChoices.DELAY_REASONS, reasonCode, "지연 사유를 선택하세요");
        String text = request.reasonText().trim();
        int bytes = text.getBytes(ElevenstXml.EUC_KR).length;
        if (bytes < DELAY_TEXT_MIN_BYTES || bytes > DELAY_TEXT_MAX_BYTES) {
            throw new IllegalArgumentException(DELAY_TEXT_MESSAGE);
        }
        elevenstSellerActions.deliveryDelay(target.account(), target.line().getOrder().getExternalOrderId(),
                target.dlvNo(), expected, reasonCode, text);
        log.info("11번가 발송지연 안내: line={} dlvNo={} until={}", orderItemId, target.dlvNo(), expected);
    }

    @Override
    public void visitPickup(Long orderItemId, ElevenstVisitPickupRequest request) {
        Target target = target(orderItemId);
        if (!OPEN_STATUSES.contains(target.line().getStatus())
                || !ElevenstRawFields.yes(target.mirror(), "visitDlvYn")) {
            throw new IllegalArgumentException(VISIT_MESSAGE);
        }
        elevenstSellerActions.visitPickup(target.account(), target.dlvNo(),
                target.line().getOrder().getExternalOrderId(), request.certCode().trim());
        List<OrderLine> delivered = orderLineRepository
                .findByExternalOrderId(target.line().getOrder().getExternalOrderId()).stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(target.account().getId()))
                .filter(l -> l.getOrderShipment() != null
                        && target.dlvNo().equals(l.getOrderShipment().getExternalShipmentId()))
                .map(l -> l.toBuilder().status(OrderStatus.DELIVERED).build())
                .toList();
        try {
            orderLineRepository.saveAll(delivered);
        } catch (Exception e) {
            log.warn("11번가 방문수령 로컬 상태 갱신 실패(11번가 처리는 성공): line={}", orderItemId, e);
        }
        log.info("11번가 방문수령 완료: line={} dlvNo={}", orderItemId, target.dlvNo());
    }

    @Override
    public void stockConfirm(Long orderItemId) {
        Target target = target(orderItemId);
        if (!ElevenstOrderList.RESERVED.code().equals(target.mirror().getPlatformStatus())) {
            throw new IllegalArgumentException(STOCK_MESSAGE);
        }
        if (target.mirror().getStockConfirmSentAt() != null) {
            throw new IllegalArgumentException(STOCK_SENT_MESSAGE);
        }
        elevenstSellerActions.stockConfirm(target.account(), target.mirror());
        try {
            elevenstOrderLineRepository.save(target.mirror().toBuilder()
                    .stockConfirmSentAt(LocalDateTime.now(SyncWindow.KST))
                    .build());
        } catch (Exception e) {
            log.warn("11번가 입고완료 기록 실패(11번가 처리는 성공): line={}", orderItemId, e);
        }
        log.info("11번가 입고완료 처리: line={} ordNo={}", orderItemId, target.mirror().getOrdNo());
    }

    /** The 11st line, its mirror and delivery number — anything else is a 400. */
    private Target target(Long orderItemId) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("주문 라인을 찾을 수 없습니다"));
        MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
        if (!Platform.ELEVENST.equals(account.getPlatform()) || line.getOrderShipment() == null) {
            throw new IllegalArgumentException(NOT_ELEVENST_MESSAGE);
        }
        ElevenstOrderLine mirror = elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(orderItemId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(NOT_ELEVENST_MESSAGE));
        return new Target(line, mirror, account, line.getOrderShipment().getExternalShipmentId());
    }

    private record Target(OrderLine line, ElevenstOrderLine mirror, MarketplaceAccount account, String dlvNo) {
    }
}
