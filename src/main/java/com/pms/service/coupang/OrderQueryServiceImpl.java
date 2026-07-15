package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.OrderItem;
import com.pms.dto.response.OrderItemResponse;
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
 * 조회는 동기화 윈도우(syncDays)와 같은 기간으로 제한한다 — 윈도우 밖 주문은 status 가 갱신되지 않아
 * stale(예: 결제완료로 얼어붙음) 하므로 표시하지 않는다. 기준일은 paidAt (주문 createdAt 미저장).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public List<OrderItemResponse> list(Long sellerId) {
        LocalDateTime from = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        List<OrderItem> items = (sellerId == null)
                ? orderItemRepository.findRecentOrders(from)
                : orderItemRepository.findRecentOrdersBySeller(sellerId, from);

        return items.stream().map(this::toResponse).toList();
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
                .orderCount(o.getOrderCount())
                .cancelCount(o.getCancelCount())
                .holdCount(o.getHoldCount())
                .purchasableQty(o.purchasableQty())
                .status(o.getStatus())
                .paidAt(o.getPaidAt())
                .build();
    }
}
