package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangReturnSyncServiceImpl 취소 보정 테스트.
 * CoupangApiClient는 @Mock 캔드 JSON, ObjectMapper는 실제, OrderItemRepository는 @Mock(find/save 검증).
 */
@ExtendWith(MockitoExtension.class)
class CoupangReturnSyncServiceImplTest {

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private OrderItemRepository orderItemRepository;

    private CoupangReturnSyncServiceImpl service;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("V0001")
                .accessKey("ak").secretKey("sk").isActive(true).build();

        CoupangProperties props = new CoupangProperties();
        props.setReturnrequestsPath("/v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests");
        props.setCancelSyncDays(7);

        service = new CoupangReturnSyncServiceImpl(
                coupangApiClient, orderItemRepository, props, new ObjectMapper());
    }

    @Test
    void syncCancels_updatesCancelCount_onMatch() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneCancel("O1", "B1", "I1", 2));
        // 기존 order_item: cancel 0 → 취소 2 반영, purchasableQty 감소(10-(2+0)=8)
        OrderItem existing = OrderItem.builder()
                .id(10L).marketplaceAccount(account).platform("COUPANG")
                .externalOrderId("O1").externalBoxId("B1").externalItemId("I1")
                .orderCount(10).cancelCount(0).holdCount(0).status("ACCEPT").build();
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                1L, "B1", "O1", "I1")).willReturn(Optional.of(existing));

        CancelSyncResult result = service.syncCancels(account);

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, times(1)).save(captor.capture());
        OrderItem saved = captor.getValue();
        assertThat(saved.getCancelCount()).isEqualTo(2);
        assertThat(saved.purchasableQty()).isEqualTo(8);
        assertThat(result.matchedUpdated()).isEqualTo(1);
    }

    @Test
    void syncCancels_ignores_whenNoMatch() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneCancel("O9", "B9", "I9", 1));
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), anyString(), anyString(), anyString())).willReturn(Optional.empty());

        CancelSyncResult result = service.syncCancels(account);

        verify(orderItemRepository, never()).save(any());
        assertThat(result.matchedUpdated()).isZero();
    }

    @Test
    void syncCancels_paginates_untilNextTokenBlank() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(pageWithToken("t"), pageWithToken(""));
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), anyString(), anyString(), anyString())).willReturn(Optional.empty());

        CancelSyncResult result = service.syncCancels(account);

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(result.pages()).isEqualTo(2);
    }

    // --- canned JSON ---

    private String oneCancel(String orderId, String boxId, String itemId, int cancelCount) {
        return """
            {"data":[
              {"orderId":"%s","receiptType":"CANCEL",
               "returnItems":[{"shipmentBoxId":"%s","vendorItemId":"%s","cancelCount":%d}]}
            ],"nextToken":""}
            """.formatted(orderId, boxId, itemId, cancelCount);
    }

    private String pageWithToken(String token) {
        String suffix = token.isBlank() ? "P2" : "P1";
        return """
            {"data":[
              {"orderId":"O-%s","returnItems":[{"shipmentBoxId":"B-%s","vendorItemId":"I-%s","cancelCount":1}]}
            ],"nextToken":"%s"}
            """.formatted(suffix, suffix, suffix, token);
    }
}
