package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.claim.ClaimSyncAdapter.ClaimSyncResult;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangClaimAdapter — 신규 창 1회 + 미완결 추적 슬라이스.
 * CoupangApiClient·repository 는 @Mock, ObjectMapper·파서·스위퍼·슬라이서는 실제 구현.
 */
@ExtendWith(MockitoExtension.class)
class CoupangClaimAdapterTest {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private ClaimUpserter claimUpserter;

    private CoupangProperties props;
    private CoupangClaimAdapter adapter;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("V0001")
                .accessKey("ak").secretKey("sk").isActive(true).build();

        props = new CoupangProperties();
        adapter = new CoupangClaimAdapter(
                coupangApiClient, props, new ObjectMapper(), new CoupangExchangeClaimParser(),
                claimUpserter, orderClaimRepository,
                new ClaimStaleSweeper(orderClaimRepository, props), new ClaimTrackingSlicer());
    }

    @Test
    void syncExchanges_newWindow_usesConfiguredWindowAndPageSizeWithoutStatusFilter() {
        // status 를 지정하면 상태 수만큼 호출이 는다(PLAN §4) — 생략 = 전 상태 조회가 전제다.
        givenNoOpenClaims();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        adapter.syncExchanges(account);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).get(anyString(), query.capture(), eq(account));
        assertThat(query.getValue())
                .contains("createdAtFrom=" + expectedFrom(props.getExchangeWindowDays()))
                .contains("maxPerPage=" + props.getExchangeMaxPerPage())
                .doesNotContain("status=");
        // 반품(yyyy-MM-dd)과 달리 시각까지 보낸다
        assertThat(query.getValue()).contains("T00:00:00").contains("T23:59:59");
    }

    @Test
    void syncExchanges_noOpenClaims_queriesOnlyTheNewWindow() {
        givenNoOpenClaims();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimSyncResult result = adapter.syncExchanges(account);

        verify(coupangApiClient, times(1)).get(anyString(), anyString(), any());
        assertThat(result.slices()).isZero();
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void syncExchanges_openClaimFrom20DaysAgo_addsSlicesOfExchangeWindowWidth() {
        // 20일 범위 ÷ 7일 폭 = 슬라이스 3개 → 신규 창 1 + 슬라이스 3
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.EXCHANGE), any()))
                .willReturn(List.of(openClaim(1L, 20)));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimSyncResult result = adapter.syncExchanges(account);

        verify(coupangApiClient, times(4)).get(anyString(), anyString(), any());
        assertThat(result.slices()).isEqualTo(3);
        assertThat(result.staleClosed()).isZero();
    }

    @Test
    void syncExchanges_capsSlices_atConfiguredMax() {
        // 교환은 폭이 7일이라 슬라이스가 쉽게 늘어난다 — 상한(D10)이 실제로 자르는지 고정한다.
        props.setClaimTrackingMaxSlices(1);
        props.setClaimStaleDays(9999);          // 스윕 비활성 스위치는 없다 — 크게 잡는 것이 유일한 구성법
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.EXCHANGE), any()))
                .willReturn(List.of(openClaim(1L, 60)));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(emptyData());

        ClaimSyncResult result = adapter.syncExchanges(account);

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());   // 신규 1 + 슬라이스 1
        verify(orderClaimRepository, never()).save(any());
        assertThat(result.slices()).isEqualTo(1);
    }

    @Test
    void syncExchanges_emptyMockResponse_countsPagesWithoutUpserting() {
        // 교환은 fixture 가 없어 local/test 의 MockCoupangApiClient 가 늘 이 응답을 준다.
        givenNoOpenClaims();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("{\"code\":200,\"data\":[]}");

        ClaimSyncResult result = adapter.syncExchanges(account);

        assertThat(result.pages()).isEqualTo(1);
        verify(claimUpserter, never()).upsert(any(), any(), any());
    }

    @Test
    void syncExchanges_receiptInResponse_upsertsAsExchange() {
        givenNoOpenClaims();
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("""
                {"data":[
                  {"exchangeId":9001,"orderId":"O-1","receiptStatus":"RECEIPT",
                   "createdAt":"2026-09-01T10:00:00",
                   "exchangeItemDtoV1s":[{"vendorItemId":"V-1","vendorItemName":"양말","quantity":1}]}
                ],"nextToken":""}
                """);

        adapter.syncExchanges(account);

        ArgumentCaptor<ClaimRecord> record = ArgumentCaptor.forClass(ClaimRecord.class);
        verify(claimUpserter).upsert(eq(account), eq(ClaimType.EXCHANGE), record.capture());
        assertThat(record.getValue().externalClaimId()).isEqualTo("9001");
    }

    @Test
    void findExchangeReceipt_matchingReceipt_returnsItWithoutUpsertingOrPagingFurther() {
        // 액션 경로(05 X3)의 재조회다 — 조회 코드를 재사용하면서 적재까지 딸려오면
        // 사용자가 버튼을 누를 때마다 동기화가 도는 셈이 된다.
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("""
                {"data":[
                  {"exchangeId":9001,"deliveryInvoiceGroupDtos":[{"shipmentBoxId":"777"}]}
                ],"nextToken":"PAGE2"}
                """);

        LocalDate day = LocalDate.of(2026, 9, 1);
        Optional<JsonNode> found = adapter.findExchangeReceipt(
                account, "9001", new SyncWindow(day, day));

        assertThat(found).isPresent();
        assertThat(found.get().path("deliveryInvoiceGroupDtos").path(0).path("shipmentBoxId").asText())
                .isEqualTo("777");
        verify(claimUpserter, never()).upsert(any(), any(), any());
        // nextToken 이 남아 있어도 찾았으면 다음 페이지를 치지 않는다.
        verify(coupangApiClient, times(1)).get(anyString(), anyString(), any());
    }

    private void givenNoOpenClaims() {
        given(orderClaimRepository.findOpen(eq(1L), eq(ClaimType.EXCHANGE), any())).willReturn(List.of());
    }

    /** 설정값에서 기대치를 만든다 — 숫자를 박으면 설정이 바뀔 때 테스트만 조용히 어긋난다. */
    private String expectedFrom(int days) {
        return LocalDate.now(SyncWindow.KST).minusDays(days).atStartOfDay().format(DATE_TIME);
    }

    /** receivedAt 은 쿠팡 createdAt = KST 벽시계다 — 기대치도 KST 로 만든다. */
    private OrderClaim openClaim(Long id, long receivedDaysAgo) {
        return OrderClaim.builder()
                .id(id).marketplaceAccount(account).platform("COUPANG").claimType(ClaimType.EXCHANGE)
                .externalClaimId("E-" + id).externalOrderId("O-" + id).externalItemId("V-" + id)
                .status(ClaimStatus.RECEIVED).platformStatus("RECEIPT")
                .receivedAt(LocalDate.now(SyncWindow.KST).minusDays(receivedDaysAgo).atTime(10, 0))
                .build();
    }

    private String emptyData() {
        return "{\"data\":[],\"nextToken\":\"\"}";
    }
}
