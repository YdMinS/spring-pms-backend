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
                .itemName("양말").ordererName("홍길동").receiverName("김철수")
                .orderCount(10).cancelCount(2).holdCount(1)
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
        assertThat(r.getOrdererName()).isEqualTo("홍길동");
        assertThat(r.getReceiverName()).isEqualTo("김철수");
        // raw 필드는 DTO에 존재하지 않음 → 직렬화/노출 불가 (목록 가벼움)
    }

    @Test
    void list_partialCancel_keepsRawStatus() {
        // 일부만 취소 → status 원본 유지, cancelled=false
        given(coupangProperties.getSyncDays()).willReturn(14);
        given(orderItemRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(sample()));

        OrderItemResponse r = service.list(null).get(0);       // orderCount 10, cancel 2, hold 1
        assertThat(r.getStatus()).isEqualTo("ACCEPT");
        assertThat(r.getEffectiveStatus()).isEqualTo("ACCEPT");
        assertThat(r.isCancelled()).isFalse();
    }

    @Test
    void list_fullyCancelledInstruct_mapsToCancelled() {
        // 상품준비중(INSTRUCT)인데 전량 취소 → effectiveStatus=CANCELLED, status 원본 보존
        given(coupangProperties.getSyncDays()).willReturn(14);
        MarketplaceAccount acc = MarketplaceAccount.builder().id(7L).platform("COUPANG").build();
        OrderItem fully = OrderItem.builder()
                .id(2L).marketplaceAccount(acc).platform("COUPANG")
                .externalOrderId("O2").externalBoxId("B2").externalItemId("I2")
                .itemName("모자").orderCount(3).cancelCount(3).holdCount(0)
                .status("INSTRUCT").build();
        given(orderItemRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(fully));

        OrderItemResponse r = service.list(null).get(0);
        assertThat(r.getStatus()).isEqualTo("INSTRUCT");       // 원본 보존
        assertThat(r.getEffectiveStatus()).isEqualTo("CANCELLED");
        assertThat(r.isCancelled()).isTrue();
        assertThat(r.getPurchasableQty()).isZero();
    }
}
