package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.dto.response.OrderItemResponse;
import com.pms.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OrderQueryServiceImpl — sellerId 분기, 엔티티→DTO 매핑(purchasableQty, raw 미노출).
 * 조회는 syncDays 윈도우(paidAt 기준)로 제한된다 — 여기서는 분기/매핑만 검증(윈도우 값은 무관).
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryServiceImplTest {

    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CoupangProperties coupangProperties;
    @InjectMocks private OrderQueryServiceImpl service;

    private OrderItem sample() {
        MarketplaceAccount acc = MarketplaceAccount.builder().id(7L).platform("COUPANG").build();
        return OrderItem.builder()
                .id(1L).marketplaceAccount(acc).platform("COUPANG")
                .externalOrderId("O1").externalBoxId("B1").externalItemId("I1")
                .itemName("양말").orderCount(10).cancelCount(2).holdCount(1)
                .status("ACCEPT").raw("{\"big\":\"json\"}").build();
    }

    @Test
    void list_filtersBySeller() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        given(orderItemRepository.findRecentOrdersBySeller(eq(5L), any(LocalDateTime.class)))
                .willReturn(List.of(sample()));

        service.list(5L);

        verify(orderItemRepository).findRecentOrdersBySeller(eq(5L), any(LocalDateTime.class));
        verify(orderItemRepository, never()).findRecentOrders(any(LocalDateTime.class));
    }

    @Test
    void list_mapsPurchasableQty() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        given(orderItemRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(sample()));

        List<OrderItemResponse> result = service.list(null);   // null → 전체

        verify(orderItemRepository).findRecentOrders(any(LocalDateTime.class));
        OrderItemResponse r = result.get(0);
        assertThat(r.getPurchasableQty()).isEqualTo(7);        // 10-(2+1)
        assertThat(r.getExternalItemId()).isEqualTo("I1");
        // raw 필드는 DTO에 존재하지 않음 → 직렬화/노출 불가 (목록 가벼움)
    }
}
