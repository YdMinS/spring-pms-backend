package com.pms.service.coupang;

import com.pms.domain.OrderItem;
import com.pms.dto.response.OrderItemResponse;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link OrderQueryService} 구현. 엔티티 → {@link OrderItemResponse} 매핑 시 raw·민감정보 제외,
 * purchasableQty 파생값 포함.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderItemRepository orderItemRepository;

    @Override
    public List<OrderItemResponse> list(Long sellerId) {
        List<OrderItem> items = (sellerId == null)
                ? orderItemRepository.findAllByOrderByPaidAtDesc()
                : orderItemRepository.findByMarketplaceAccount_Seller_IdOrderByPaidAtDesc(sellerId);

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
