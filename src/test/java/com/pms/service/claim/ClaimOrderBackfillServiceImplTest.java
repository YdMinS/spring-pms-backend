package com.pms.service.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.claim.ClaimOrderBackfillService.BackfillResult;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.OrderItemUpserter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ClaimOrderBackfillServiceImpl — orderId 단위 dedupe, 회차 상한, 주문 단위 격리, 쿨다운 전파.
 *
 * ⚠️ {@code CoupangProperties} 는 @Mock 이 아니라 실제 인스턴스 + setter 다(형제 테스트
 * {@code CoupangReturnSyncServiceImplTest} 관례). 상한·비활성 케이스에서 스텁이 안 쓰여
 * strict stubs 의 UnnecessaryStubbingException 이 나는 것도 막는다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimOrderBackfillServiceImplTest {

    private static final String ORDER_JSON = """
            {"code":200,"data":[{"shipmentBoxId":"BOX1","orderId":"%s","status":"FINAL_DELIVERY",
              "orderItems":[{"vendorItemId":"V1","shippingCount":1,"cancelCount":1}]}]}""";

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private CoupangApiClient coupangApiClient;
    @Mock private OrderItemUpserter orderItemUpserter;
    @Mock private ClaimUpserter claimUpserter;

    private CoupangProperties props;
    private ClaimOrderBackfillServiceImpl service;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("V0001")
                .accessKey("ak").secretKey("sk").isActive(true).build();

        props = new CoupangProperties();
        props.setOrdersheetByOrderPath("/api/v4/vendors/{vendorId}/{orderId}/ordersheets");
        props.setClaimBackfillMaxOrders(20);
        props.setClaimBackfillMaxAttempts(3);

        service = new ClaimOrderBackfillServiceImpl(
                orderClaimRepository, coupangApiClient, orderItemUpserter, claimUpserter,
                props, new ObjectMapper());
    }

    @Test
    void backfill_multipleClaimsOnSameOrder_fetchesOncePerOrderId() {
        // D13: 호출 수는 미연결 클레임 수가 아니라 미연결 orderId 수에 비례한다
        givenUnlinked(claim(1L, "O-1"), claim(2L, "O-1"), claim(3L, "O-2"));
        givenOrderFound("O-1", "O-2");

        BackfillResult result = service.backfill(account);

        verify(coupangApiClient, times(2)).get(anyString(), eq(""), any());
        verify(claimUpserter).relink(1L);
        verify(claimUpserter).relink(2L);
        verify(claimUpserter).relink(3L);
        assertThat(result.ordersFetched()).isEqualTo(2);
    }

    @Test
    void backfill_moreOrdersThanCap_deferSurplusWithoutConsumingAttempts() {
        props.setClaimBackfillMaxOrders(2);
        givenUnlinked(claim(1L, "O-1"), claim(2L, "O-2"),
                claim(3L, "O-3"), claim(4L, "O-4"), claim(5L, "O-5"));
        givenOrderFound("O-1", "O-2");

        BackfillResult result = service.backfill(account);

        verify(coupangApiClient, times(2)).get(anyString(), eq(""), any());
        verify(claimUpserter).relink(1L);
        verify(claimUpserter).relink(2L);
        verify(claimUpserter, never()).relink(3L);
        // 이월이지 실패가 아니다 — 시도횟수를 소모하면 안 된다
        verify(claimUpserter, never()).recordMatchAttempt(anyLong());
        assertThat(result.ordersFetched()).isEqualTo(2);
    }

    @Test
    void backfill_oneOrderFails_continuesWithNextOrder() {
        givenUnlinked(claim(1L, "O-1"), claim(2L, "O-2"));
        given(coupangApiClient.get(contains("O-1"), eq(""), any()))
                .willThrow(new RuntimeException("쿠팡 500"));
        given(coupangApiClient.get(contains("O-2"), eq(""), any()))
                .willReturn(ORDER_JSON.formatted("O-2"));

        BackfillResult result = service.backfill(account);

        verify(claimUpserter).recordMatchAttempt(1L);
        verify(claimUpserter, never()).relink(1L);
        verify(orderItemUpserter, times(1)).upsertBox(eq(account), any());
        verify(claimUpserter).relink(2L);
        assertThat(result.ordersFetched()).isEqualTo(1);
    }

    @Test
    void backfill_abnormalEnvelope_recordsAttemptWithoutUpsert() {
        givenUnlinked(claim(1L, "O-1"));
        given(coupangApiClient.get(anyString(), eq(""), any())).willReturn("{\"code\":500}");

        BackfillResult result = service.backfill(account);

        verify(orderItemUpserter, never()).upsertBox(any(), any());
        verify(claimUpserter, never()).relink(anyLong());
        verify(claimUpserter).recordMatchAttempt(1L);
        assertThat(result.ordersFetched()).isZero();
    }

    @Test
    void backfill_countsOnlySuccessfulRelinks() {
        givenUnlinked(claim(1L, "O-1"), claim(2L, "O-1"), claim(3L, "O-2"));
        givenOrderFound("O-1", "O-2");
        given(claimUpserter.relink(1L)).willReturn(true);
        given(claimUpserter.relink(2L)).willReturn(false);
        given(claimUpserter.relink(3L)).willReturn(true);

        BackfillResult result = service.backfill(account);

        assertThat(result).isEqualTo(new BackfillResult(2, 2));
    }

    @Test
    void backfill_rateLimited_propagatesWithoutConsumingAttempts() {
        // 쿨다운 창에는 매 호출이 즉시 던진다 — 삼키면 20개 orderId 전부가 시도횟수를 먹어
        // 일시적 장애만으로 미연결 클레임이 영구 제외된다(D13 의 포기 조건은 "쿠팡에 없는 주문"뿐).
        givenUnlinked(claim(1L, "O-1"), claim(2L, "O-2"));
        willThrow(new CoupangRateLimitedException(Instant.now().plusSeconds(600)))
                .given(coupangApiClient).get(contains("O-1"), eq(""), any());

        assertThatThrownBy(() -> service.backfill(account))
                .isInstanceOf(CoupangRateLimitedException.class);

        verify(claimUpserter, never()).recordMatchAttempt(anyLong());
        verify(coupangApiClient, never()).get(contains("O-2"), eq(""), any());
    }

    private void givenUnlinked(OrderClaim... claims) {
        given(orderClaimRepository.findUnlinked(eq(1L), eq(3), any(Pageable.class)))
                .willReturn(List.of(claims));
    }

    private void givenOrderFound(String... orderIds) {
        for (String orderId : orderIds) {
            given(coupangApiClient.get(contains(orderId), eq(""), any()))
                    .willReturn(ORDER_JSON.formatted(orderId));
        }
    }

    /** 백필은 id·externalOrderId 만 읽는다 — 나머지는 채우지 않아도 된다. */
    private OrderClaim claim(long id, String orderId) {
        return OrderClaim.builder().id(id).externalOrderId(orderId)
                .externalItemId("V1").receivedAt(LocalDateTime.now()).build();
    }
}
