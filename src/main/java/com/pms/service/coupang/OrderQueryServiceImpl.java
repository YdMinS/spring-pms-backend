package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.service.elevenst.ElevenstOrderList;
import com.pms.service.elevenst.ElevenstRawFields;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderQueryService} implementation. Three order layers + the platform mirrors (Coupang · 11st,
 * FEATURE_2610_17 / D5) → {@link OrderItemResponse} mapping (no raw · sensitive data, with the derived
 * purchasableQty).
 *
 * <p>Without a period (the default) the query is limited to the sync window (syncDays) — orders outside it get no
 * status updates and go stale (e.g. frozen at paid). With an explicit from/to it also reads the past beyond that
 * window, and telling the user about staleness is the client's job (FEATURE_2609_08 D1·D7). The reference date is
 * {@code orders.ordered_at}.
 *
 * <p>🔴 The platform originals (vendorItemId · prdStckNo · platform_status) are not read again per line but
 * <b>once</b> per batch of line ids into a Map (FEATURE_2609_26 / 04 §3-3) — a back reference in core would leak
 * the platform into core and bring N+1 with it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderRepository orderRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public List<OrderItemResponse> list(Long sellerId, LocalDate from, LocalDate to) {
        List<OrderLine> lines = (from == null && to == null)
                ? defaultWindow(sellerId)
                : period(sellerId, from, to);
        Map<Long, CoupangOrderLine> mirrors = mirrorsByLineId(lines);
        Map<Long, ElevenstOrderLine> elevenstMirrors = elevenstMirrorsByLineId(lines);
        return lines.stream()
                .map(line -> toResponse(line, mirrors.get(line.getId()), elevenstMirrors.get(line.getId())))
                .toList();
    }

    @Override
    public List<OrderMonthResponse> months() {
        return orderRepository.countByMonth().stream()
                .map(row -> new OrderMonthResponse(
                        String.format("%d-%02d", ((Number) row[0]).intValue(), ((Number) row[1]).intValue()),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /** 기본 창 — 기존 동작 그대로(오늘 − syncDays, 상한 없음). */
    private List<OrderLine> defaultWindow(Long sellerId) {
        LocalDateTime start = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        return (sellerId == null)
                ? orderRepository.findRecentOrders(start)
                : orderRepository.findRecentOrdersBySeller(sellerId, start);
    }

    /** 기간 지정 — 상한은 to 다음날 00:00(배타적)이라 to 당일이 포함된다(2609_08 D4). */
    private List<OrderLine> period(Long sellerId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime endExclusive = to.plusDays(1).atStartOfDay();
        return (sellerId == null)
                ? orderRepository.findOrdersInPeriod(start, endExclusive)
                : orderRepository.findOrdersInPeriodBySeller(sellerId, start, endExclusive);
    }

    /** 라인 id → 쿠팡 거울 행. 빈 목록으로 IN 을 날리지 않는다. */
    private Map<Long, CoupangOrderLine> mirrorsByLineId(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        return coupangOrderLineRepository.findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
    }

    /** Line id → 11st mirror row (FEATURE_2610_17 / D5). No IN query for an empty list. */
    private Map<Long, ElevenstOrderLine> elevenstMirrorsByLineId(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        return elevenstOrderLineRepository.findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
    }

    private OrderItemResponse toResponse(OrderLine line, CoupangOrderLine mirror, ElevenstOrderLine elevenstMirror) {
        var order = line.getOrder();
        // 전량취소는 저장되지 않는 파생값이라 여기서 만들어 낸다(PLAN D26).
        OrderStatus effective = line.effectiveStatus();
        return OrderItemResponse.builder()
                .id(line.getId())
                .marketplaceAccountId(order.getMarketplaceAccount().getId())
                .platform(order.getPlatform().name())
                .externalOrderId(order.getExternalOrderId())
                .externalBoxId(line.getOrderShipment() != null
                        ? line.getOrderShipment().getExternalShipmentId() : null)
                // 11st: externalItemId = prdStckNo (the vendorItemId slot), platformStatus = list code (D5).
                .externalItemId(mirror != null ? mirror.getVendorItemId()
                        : elevenstMirror != null ? elevenstMirror.getPrdStckNo() : null)
                .itemName(line.getItemName())
                .ordererName(order.getOrdererName())
                .receiverName(order.getReceiverName())
                .orderCount(line.getOrderQty())
                .cancelCount(line.getCancelQty())
                .holdCount(line.getHoldQty())
                .purchasableQty(line.purchasableQty())
                .status(effective != null ? effective.name() : null)
                .platformStatus(mirror != null ? mirror.getPlatformStatus()
                        : elevenstMirror != null ? elevenstMirror.getPlatformStatus() : null)
                .cancelled(line.isFullyCancelled())
                // D29 — 표시용 상태가 PAID 인 라인에서만(전량 취소는 저장 status 가 PAID 로 남는다). 읽을 때 거른다 — 칸은 지우지 않는다(D1).
                .internalStage(effective == OrderStatus.PAID && line.getOrderShipment() != null
                        && line.getOrderShipment().getInternalStage() != null
                        ? line.getOrderShipment().getInternalStage().name() : null)
                .paidAt(order.getOrderedAt())
                .unitPrice(line.getUnitPrice())
                .lineAmount(line.getLineAmount())
                .discountAmount(line.getDiscountAmount())
                .platformDiscountAmount(line.getPlatformDiscountAmount())
                // FEATURE_2610_17 / D66 · D67: 11st only — false for every other line.
                .visitPickup(ElevenstRawFields.yes(elevenstMirror, "visitDlvYn"))
                .reservedSale(elevenstMirror != null
                        && ElevenstOrderList.RESERVED.code().equals(elevenstMirror.getPlatformStatus()))
                .stockConfirmSent(elevenstMirror != null && elevenstMirror.getStockConfirmSentAt() != null)
                .build();
    }
}
