package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderRepository;
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
 * {@link OrderQueryService} 구현. 주문 3층 + 쿠팡 거울 → {@link OrderItemResponse} 매핑
 * (raw·민감정보 제외, purchasableQty 파생값 포함).
 *
 * <p>조회 기간을 지정하지 않으면(기본값) 동기화 윈도우(syncDays)와 같은 기간으로 제한한다 — 윈도우 밖 주문은
 * 상태가 갱신되지 않아 stale(예: 결제완료로 얼어붙음) 하기 때문이다. 단, from/to 를 명시하면 그 창을
 * 벗어난 과거도 조회하며, stale 고지는 클라이언트 책임이다(FEATURE_2609_08 D1·D7).
 * 기준일은 {@code orders.ordered_at}.
 *
 * <p>🔴 플랫폼 원문(vendorItemId·platform_status)은 라인마다 다시 조회하지 않고 라인 id 묶음으로
 * <b>한 번에</b> 읽어 Map 으로 쓴다(FEATURE_2609_26 / 04 §3-3) — core 에 역참조를 두면 플랫폼이
 * core 로 새어 들어오고 N+1 이 따라온다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderRepository orderRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public List<OrderItemResponse> list(Long sellerId, LocalDate from, LocalDate to) {
        List<OrderLine> lines = (from == null && to == null)
                ? defaultWindow(sellerId)
                : period(sellerId, from, to);
        Map<Long, CoupangOrderLine> mirrors = mirrorsByLineId(lines);
        return lines.stream().map(line -> toResponse(line, mirrors.get(line.getId()))).toList();
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

    private OrderItemResponse toResponse(OrderLine line, CoupangOrderLine mirror) {
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
                .externalItemId(mirror != null ? mirror.getVendorItemId() : null)
                .itemName(line.getItemName())
                .ordererName(order.getOrdererName())
                .receiverName(order.getReceiverName())
                .orderCount(line.getOrderQty())
                .cancelCount(line.getCancelQty())
                .holdCount(line.getHoldQty())
                .purchasableQty(line.purchasableQty())
                .status(effective != null ? effective.name() : null)
                .platformStatus(mirror != null ? mirror.getPlatformStatus() : null)
                .cancelled(line.isFullyCancelled())
                .paidAt(order.getOrderedAt())
                .unitPrice(line.getUnitPrice())
                .lineAmount(line.getLineAmount())
                .discountAmount(line.getDiscountAmount())
                .platformDiscountAmount(line.getPlatformDiscountAmount())
                .build();
    }
}
