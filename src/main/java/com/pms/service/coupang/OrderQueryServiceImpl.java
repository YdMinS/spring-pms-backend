package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.OrderItem;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link OrderQueryService} 구현. 엔티티 → {@link OrderItemResponse} 매핑 시 raw·민감정보 제외,
 * purchasableQty 파생값 포함.
 *
 * 조회 기간을 지정하지 않으면(기본값) 동기화 윈도우(syncDays)와 같은 기간으로 제한한다 — 윈도우 밖 주문은
 * status 가 갱신되지 않아 stale(예: 결제완료로 얼어붙음) 하기 때문이다. 단, from/to 를 명시하면 그 창을
 * 벗어난 과거도 조회하며, stale 고지는 클라이언트 책임이다(FEATURE_2609_08 D1·D7).
 * 기준일은 paidAt (주문 createdAt 미저장).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public List<OrderItemResponse> list(Long sellerId, LocalDate from, LocalDate to) {
        List<OrderItem> items = (from == null && to == null)
                ? defaultWindow(sellerId)
                : period(sellerId, from, to);
        return items.stream().map(this::toResponse).toList();
    }

    @Override
    public List<OrderMonthResponse> months() {
        return orderItemRepository.countByMonth().stream()
                .map(row -> new OrderMonthResponse(
                        String.format("%d-%02d", ((Number) row[0]).intValue(), ((Number) row[1]).intValue()),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /** 기본 창 — 기존 동작 그대로(오늘 − syncDays, 상한 없음). */
    private List<OrderItem> defaultWindow(Long sellerId) {
        LocalDateTime start = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        return (sellerId == null)
                ? orderItemRepository.findRecentOrders(start)
                : orderItemRepository.findRecentOrdersBySeller(sellerId, start);
    }

    /** 기간 지정 — 상한은 to 다음날 00:00(배타적)이라 to 당일이 포함된다(PLAN D4). */
    private List<OrderItem> period(Long sellerId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime endExclusive = to.plusDays(1).atStartOfDay();
        return (sellerId == null)
                ? orderItemRepository.findOrdersInPeriod(start, endExclusive)
                : orderItemRepository.findOrdersInPeriodBySeller(sellerId, start, endExclusive);
    }

    private OrderItemResponse toResponse(OrderItem o) {
        return OrderItemResponse.builder()
                .id(o.getId())
                .marketplaceAccountId(o.getMarketplaceAccount().getId())
                .platform(o.getPlatform())
                .externalOrderId(o.getExternalOrderId())
                .externalBoxId(o.getExternalBoxId())
                .externalItemId(o.getExternalItemId())
                .itemName(o.getItemName())
                .ordererName(o.getOrdererName())
                .receiverName(o.getReceiverName())
                .orderCount(o.getOrderCount())
                .cancelCount(o.getCancelCount())
                .holdCount(o.getHoldCount())
                .purchasableQty(o.purchasableQty())
                .status(o.getStatus())
                .effectiveStatus(o.effectiveStatus())
                .cancelled(o.isFullyCancelled())
                .paidAt(o.getPaidAt())
                .build();
    }
}
