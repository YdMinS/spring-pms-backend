package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.claim.ClaimStaleSweeper;
import com.pms.service.claim.ClaimTrackingSlicer;
import com.pms.service.claim.ClaimUpserter;
import com.pms.service.claim.CoupangReturnClaimParser;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.ClaimTrackingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private OrderClaimRepository orderClaimRepository;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private CoupangProperties props;
    private CoupangReturnSyncServiceImpl service;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("V0001")
                .accessKey("ak").secretKey("sk").isActive(true).build();

        props = new CoupangProperties();
        props.setReturnrequestsPath("/v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests");
        props.setCancelSyncDays(7);

        // 스윕·슬라이스는 06 에서 컴포넌트로 추출됐다 — 목이 아니라 실제 구현을 넣어 기존 단언을 그대로 유지한다.
        service = new CoupangReturnSyncServiceImpl(
                coupangApiClient, orderItemRepository, props, new ObjectMapper(),
                new CoupangReturnClaimParser(), claimUpserter, orderClaimRepository,
                new ClaimStaleSweeper(orderClaimRepository, props), new ClaimTrackingSlicer());
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

    // --- 신규 조회 창 (delta, D6) ---

    @Test
    void newWindow_firstRun_usesCancelSyncDays() {
        // lastClaimSyncAt = null → "아직 한 번도 완료 안 함" = 현행 동작 그대로(하한).
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        service.syncCancels(account);

        assertThat(capturedQueries()).allMatch(q -> q.contains(expectedFrom(props.getCancelSyncDays())));
    }

    @Test
    void newWindow_idleAccount_widensToElapsedPlusOne() {
        // 20일 쉰 계정 → 21일 창. 오래 쉰 계정만 자동으로 넓어진다(고정 숫자 튜닝 불필요).
        account = account.toBuilder().lastClaimSyncAt(utcDaysAgo(20)).build();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        service.syncCancels(account);

        assertThat(capturedQueries()).allMatch(q -> q.contains(expectedFrom(21)));
    }

    @Test
    void newWindow_recentSuccess_neverNarrowsBelowCancelSyncDays() {
        // 요지: 창은 좁아지지 않는다 — 취소 보정이 이 창을 공유하기 때문(D15·D16).
        account = account.toBuilder().lastClaimSyncAt(utcDaysAgo(0)).build();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        service.syncCancels(account);

        assertThat(capturedQueries()).allMatch(q -> q.contains(expectedFrom(props.getCancelSyncDays())));
    }

    // --- STALE 스윕 (D11) ---

    @Test
    void trackOpenClaims_sweepsOnlyClaimsOlderThanStaleDays() {
        // 같은 판정 축이라 한 테스트에 모은다: 31일 전(강제 종결) / 29일 전(유지) / 이미 종결된 건은 애초에 조회 대상 아님.
        OrderClaim old = openClaim(1L, 31);
        OrderClaim fresh = openClaim(2L, 29);
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.RETURN), any()))
                .willReturn(List.of(old, fresh));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimTrackingResult result = service.trackOpenClaims(account);

        ArgumentCaptor<OrderClaim> saved = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(1L);
        assertThat(saved.getValue().getStatus()).isEqualTo(ClaimStatus.STALE);
        assertThat(result.staleClosed()).isEqualTo(1);
    }

    @Test
    void trackOpenClaims_asksForClosedStatuses_derivedFromIsOpen() {
        // "무엇이 종결인가"의 정의가 두 벌이 되지 않게 고정한다.
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.RETURN), any())).willReturn(List.of());

        service.trackOpenClaims(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ClaimStatus>> closed = ArgumentCaptor.forClass(Collection.class);
        verify(orderClaimRepository).findOpen(eq(1L), eq(ClaimType.RETURN), closed.capture());
        assertThat(closed.getValue()).containsExactlyInAnyOrderElementsOf(ClaimStatus.closedStatuses());
        assertThat(ClaimStatus.closedStatuses()).containsExactlyInAnyOrder(
                ClaimStatus.DONE, ClaimStatus.REJECTED, ClaimStatus.WITHDRAWN, ClaimStatus.STALE);
    }

    // --- 추적 슬라이스 (D7·D10) ---

    @Test
    void trackOpenClaims_noOpenClaims_doesNotCallCoupang() {
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.RETURN), any())).willReturn(List.of());

        ClaimTrackingResult result = service.trackOpenClaims(account);

        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
        assertThat(result.slices()).isZero();
    }

    @Test
    void trackOpenClaims_queriesOneSlice_withoutStatusFilter() {
        // status 를 슬라이스마다 4번 도는 형태로 만들면 호출이 4배가 된다 — 생략 = 전 상태 조회가 전제(PLAN §4).
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.RETURN), any()))
                .willReturn(List.of(openClaim(1L, 5)));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimTrackingResult result = service.trackOpenClaims(account);

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).get(anyString(), queries.capture(), any());
        assertThat(queries.getValue()).contains(expectedFrom(5)).doesNotContain("status=");
        assertThat(result.slices()).isEqualTo(1);
    }

    @Test
    void trackOpenClaims_capsSlices_atConfiguredMax() {
        // 60일 범위 = 30일 폭 슬라이스 2개지만 상한 1 로 잘린다(D10). 잘리는 쪽은 항상 최신 구간이라
        // 신규 조회 창이 이미 덮는다.
        props.setClaimTrackingMaxSlices(1);
        props.setClaimStaleDays(9999);          // 스윕 비활성 스위치는 없다 — 크게 잡는 것이 유일한 구성법
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.RETURN), any()))
                .willReturn(List.of(openClaim(1L, 60)));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimTrackingResult result = service.trackOpenClaims(account);

        verify(coupangApiClient, times(1)).get(anyString(), anyString(), any());
        verify(orderClaimRepository, never()).save(any());
        assertThat(result.slices()).isEqualTo(1);
    }

    // --- helpers ---

    private List<String> capturedQueries() {
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(5)).get(anyString(), queries.capture(), any());
        return queries.getAllValues();
    }

    private String expectedFrom(int days) {
        return "createdAtFrom=" + LocalDate.now(SyncWindow.KST).minusDays(days).format(DATE_FORMAT);
    }

    /**
     * lastClaimSyncAt 픽스처 — 서버 시각(UTC) naive 다.
     * LocalDateTime.now().minusDays(n) 을 쓰면 recentSince 가 UTC 로 해석해 KST 15시 이후에만 하루
     * 어긋나는 플래키 테스트가 된다(SyncWindowTest.utcDaysAgo 와 같은 방식).
     */
    private static LocalDateTime utcDaysAgo(long days) {
        return LocalDate.now(SyncWindow.KST).minusDays(days).atTime(3, 0);
    }

    /** receivedAt 은 쿠팡 createdAt = KST 벽시계다 — 기대치도 KST 로 만든다. */
    private OrderClaim openClaim(Long id, long receivedDaysAgo) {
        return OrderClaim.builder()
                .id(id).marketplaceAccount(account).platform("COUPANG").claimType(ClaimType.RETURN)
                .externalClaimId("R-" + id).externalOrderId("O-" + id).externalItemId("V-" + id)
                .status(ClaimStatus.RECEIVED).platformStatus("UC")
                .receivedAt(LocalDate.now(SyncWindow.KST).minusDays(receivedDaysAgo).atTime(10, 0))
                .build();
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
