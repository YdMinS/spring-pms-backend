package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import com.pms.service.claim.ClaimUpserter;
import com.pms.service.claim.CoupangReturnClaimParser;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangReturnSyncServiceImpl 취소 보정 테스트 — CANCEL 배치 1 + status 4종 배치.
 * CoupangApiClient는 @Mock 캔드 JSON, ObjectMapper는 실제, OrderItemRepository는 @Mock(find/save 검증).
 */
@ExtendWith(MockitoExtension.class)
class CoupangReturnSyncServiceImplTest {

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ClaimUpserter claimUpserter;      // 클레임 적재는 별도 트랜잭션 — 취소 보정과 분리 검증

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
                coupangApiClient, orderItemRepository, props, new ObjectMapper(),
                new CoupangReturnClaimParser(), claimUpserter);
    }

    @Test
    void syncCancels_updatesCancelCount_onMatch() {
        given(coupangApiClient.get(anyString(), contains("cancelType=CANCEL"), any()))
                .willReturn(oneCancel("O1", "B1", "I1", 2));
        given(coupangApiClient.get(anyString(), contains("status="), any())).willReturn(emptyData());
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
    void syncCancels_appliesReturnTypeCancel_fromStatusBatch() {
        // 발송전 주문의 판매자 품절취소는 쿠팡에서 receiptType=RETURN 으로 기록되어 cancelType=CANCEL
        // 배치엔 안 잡힌다 → status 4종 날짜창 배치가 그 취소를 잡아 cancel_count 를 보정한다.
        given(coupangApiClient.get(anyString(), contains("cancelType=CANCEL"), any())).willReturn(emptyData());
        given(coupangApiClient.get(anyString(), contains("status=RU"), any())).willReturn(oneReturn("O1", "B1", "I1", 2));
        given(coupangApiClient.get(anyString(), contains("status=UC"), any())).willReturn(emptyData());
        given(coupangApiClient.get(anyString(), contains("status=CC"), any())).willReturn(emptyData());
        given(coupangApiClient.get(anyString(), contains("status=PR"), any())).willReturn(emptyData());

        OrderItem existing = OrderItem.builder()
                .id(20L).marketplaceAccount(account).platform("COUPANG")
                .externalOrderId("O1").externalBoxId("B1").externalItemId("I1")
                .orderCount(2).cancelCount(0).holdCount(0).status("INSTRUCT").build();
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                1L, "B1", "O1", "I1")).willReturn(Optional.of(existing));

        CancelSyncResult result = service.syncCancels(account);

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getCancelCount()).isEqualTo(2);
        assertThat(captor.getValue().isFullyCancelled()).isTrue();      // 2 >= orderCount 2 → 전량취소
        assertThat(result.matchedUpdated()).isEqualTo(1);
    }

    @Test
    void syncCancels_queriesStatusBatch_withoutOrderId() {
        // 이번 변경의 본질: 계정당 호출이 주문 수와 무관하게 CANCEL 1 + status 4 = 5 로 고정된다.
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        service.syncCancels(account);

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(5)).get(anyString(), queries.capture(), any());

        List<String> all = queries.getAllValues();
        assertThat(all).filteredOn(q -> q.contains("cancelType=CANCEL")).hasSize(1);
        assertThat(all).filteredOn(q -> q.contains("status=RU")).hasSize(1);
        assertThat(all).filteredOn(q -> q.contains("status=UC")).hasSize(1);
        assertThat(all).filteredOn(q -> q.contains("status=CC")).hasSize(1);
        assertThat(all).filteredOn(q -> q.contains("status=PR")).hasSize(1);
        // N+1 재발 방지의 핵심 단언 — 주문번호 단위 조회로 되돌아가면 여기서 깨진다.
        assertThat(all).noneMatch(q -> q.contains("orderId="));

        // 창 통일: status 배치도 cancelSyncDays(7) 를 쓴다(구 RECON_LOOKBACK_DAYS=30 폐기).
        String expectedFrom = "createdAtFrom="
                + LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(7).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertThat(all).filteredOn(q -> q.contains("status=")).allMatch(q -> q.contains(expectedFrom));
    }

    @Test
    void syncCancels_paginates_untilNextTokenBlank() {
        given(coupangApiClient.get(anyString(), contains("cancelType=CANCEL"), any()))
                .willReturn(pageWithToken("t"), pageWithToken(""));
        given(coupangApiClient.get(anyString(), contains("status="), any())).willReturn(emptyData());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), anyString(), anyString(), anyString())).willReturn(Optional.empty());

        CancelSyncResult result = service.syncCancels(account);

        verify(coupangApiClient, times(2)).get(anyString(), contains("cancelType=CANCEL"), any());
        // CANCEL 2페이지 + status 4종 × 1페이지(빈 응답도 pages++) = 6 — 합계가 상수임을 함께 고정한다.
        assertThat(result.pages()).isEqualTo(6);
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

    private String emptyData() {
        return "{\"data\":[],\"nextToken\":\"\"}";
    }

    /** 판매자 품절취소 응답 — receiptType=RETURN (cancelType=CANCEL 배치엔 안 잡히는 형태). */
    private String oneReturn(String orderId, String boxId, String itemId, int cancelCount) {
        return """
            {"data":[
              {"orderId":"%s","receiptType":"RETURN","receiptStatus":"RETURNS_COMPLETED",
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
