package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangInquiryAdapter — 앵커·슬라이스(D8·D9·D10) · 페이징 가드 · 유형 간 실패 격리.
 *
 * 쿠팡 호출은 {@link CoupangApiClient} 목이다(HTTP 없음). 유형이 2종이라 한 회차의 호출 수는
 * "슬라이스 × 페이지 × 2" 이며, 그 곱이 폭주하지 않는 것이 이 테스트의 관심사다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangInquiryAdapterTest {

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private CoupangProductInquiryParser coupangProductInquiryParser;
    @Mock private CoupangCallCenterInquiryParser coupangCallCenterInquiryParser;
    @Mock private InquiryUpserter inquiryUpserter;
    @Mock private InquiryStaleSweeper inquiryStaleSweeper;
    @Mock private CustomerInquiryRepository customerInquiryRepository;

    private final CoupangProperties coupangProperties = new CoupangProperties();
    private CoupangInquiryAdapter adapter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A0001", null)
            .id(7L).platform(Platform.COUPANG).build();

    @BeforeEach
    void setUp() {
        adapter = new CoupangInquiryAdapter(coupangApiClient, coupangProperties, new ObjectMapper(),
                coupangProductInquiryParser, coupangCallCenterInquiryParser, inquiryUpserter,
                inquiryStaleSweeper, customerInquiryRepository);
    }

    @Test
    void slices_anchorIsTheOlderOfLastSyncAndOldestUnanswered() {
        LocalDate today = LocalDate.now(SyncWindow.KST);
        // lastInquirySyncAt 은 서버 UTC naive 다 — 최근(어제)으로 두고, 미답변은 20일 전으로 둔다.
        MarketplaceAccount synced = account.toBuilder()
                .lastInquirySyncAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
                .build();
        givenOldestUnanswered(today.minusDays(20).atStartOfDay());

        List<SyncWindow> slices = adapter.slices(synced);

        // 앵커가 더 오래된 쪽(미답변 20일 전)이라 창이 하나로 끝나지 않는다.
        assertThat(slices.get(0).from()).isEqualTo(today.minusDays(20));
        assertThat(slices).hasSizeGreaterThan(1);
        assertThat(slices.get(slices.size() - 1).to()).isEqualTo(today);
    }

    @Test
    void slices_noAnchor_usesLastSevenDays() {
        LocalDate today = LocalDate.now(SyncWindow.KST);
        givenNoUnanswered();

        List<SyncWindow> slices = adapter.slices(account);

        assertThat(slices).hasSize(1);
        // 양끝 포함 7일 = from + 6. from + 7 이면 8일이라 쿠팡이 창을 거절한다.
        assertThat(slices.get(0).from()).isEqualTo(today.minusDays(6));
        assertThat(slices.get(0).to()).isEqualTo(today);
    }

    @Test
    void slices_neverSpanMoreThanSevenDaysInclusive() {
        // 앵커가 멀어도(D9 상한까지) 슬라이스 하나하나는 쿠팡 상한 안에 있어야 한다.
        givenOldestUnanswered(LocalDate.now(SyncWindow.KST).minusDays(400).atStartOfDay());

        List<SyncWindow> slices = adapter.slices(account);

        assertThat(slices).allSatisfy(slice ->
                assertThat(ChronoUnit.DAYS.between(slice.from(), slice.to())).isLessThanOrEqualTo(6));
    }

    @Test
    void slices_veryOldUnanswered_isCappedByStaleFloorAndMaxSlices() {
        // D9 상한(30일) 밖의 미답변은 앵커를 더 끌지 못하고, 슬라이스는 상한(6)을 넘지 않는다.
        givenOldestUnanswered(LocalDate.now(SyncWindow.KST).minusDays(400).atStartOfDay());

        List<SyncWindow> slices = adapter.slices(account);

        assertThat(slices).hasSizeLessThanOrEqualTo(coupangProperties.getInquiryTrackingMaxSlices());
        assertThat(slices.get(0).from())
                .isEqualTo(LocalDate.now(SyncWindow.KST).minusDays(coupangProperties.getInquiryStaleDays()));
    }

    @Test
    void syncInquiries_pagesUntilTotalPagesAndCallsBothTypes() {
        givenNoUnanswered();
        given(inquiryStaleSweeper.sweep(account)).willReturn(2);
        given(coupangApiClient.get(anyString(), anyString(), eq(account)))
                .willReturn(page(2), page(2), page(2), page(2));

        InquirySyncAdapter.InquirySyncResult result = adapter.syncInquiries(account);

        // 1 슬라이스 × 2 페이지 × 유형 2종 = 4 호출.
        verify(coupangApiClient, times(4)).get(anyString(), anyString(), eq(account));
        assertThat(result.pages()).isEqualTo(4);
        assertThat(result.slices()).isEqualTo(2);          // 유형 합산
        assertThat(result.staleClosed()).isEqualTo(2);
        // 필수 파라미터가 유형별로 다르다 — 생략하면 쿠팡이 400 을 준다.
        verify(coupangApiClient, atLeastOnce()).get(contains("onlineInquiries"),
                contains("answeredType=ALL"), eq(account));
        verify(coupangApiClient, atLeastOnce()).get(contains("callCenterInquiries"),
                contains("partnerCounselingStatus=NONE"), eq(account));
        // vendorId 는 경로뿐 아니라 쿼리에도 실려야 한다 — 빠지면 두 API 모두 목록을 돌려주지 않는다.
        verify(coupangApiClient, times(4)).get(anyString(), contains("vendorId=A0001"), eq(account));
    }

    @Test
    void syncInquiries_runawayTotalPages_stopsAtMaxPages() {
        givenNoUnanswered();
        given(coupangApiClient.get(anyString(), anyString(), eq(account))).willReturn(page(9999));

        InquirySyncAdapter.InquirySyncResult result = adapter.syncInquiries(account);

        assertThat(result.pages()).isEqualTo(CoupangInquiryAdapter.MAX_PAGES * 2);   // 유형 2종
    }

    @Test
    void syncInquiries_oneTypeFails_stillRunsTheOtherThenRethrows() {
        givenNoUnanswered();
        // 상품문의는 실패, 고객센터는 성공 — 둘 다 돌린 뒤 마지막에 예외를 던진다.
        given(coupangApiClient.get(contains("onlineInquiries"), anyString(), eq(account)))
                .willThrow(new IllegalStateException("boom"));
        given(coupangApiClient.get(contains("callCenterInquiries"), anyString(), eq(account)))
                .willReturn(page(1));

        assertThatThrownBy(() -> adapter.syncInquiries(account))
                .isInstanceOf(IllegalStateException.class);

        // 실패를 결과 필드로 돌려주면 파사드가 성공으로 보고 lastInquirySyncAt 을 갱신해 구간이 사라진다.
        verify(coupangApiClient).get(contains("callCenterInquiries"), anyString(), eq(account));
    }

    private void givenOldestUnanswered(LocalDateTime inquiredAt) {
        given(customerInquiryRepository.findTopByMarketplaceAccount_IdAndStatusOrderByInquiredAtAsc(
                eq(7L), eq(InquiryStatus.UNANSWERED)))
                .willReturn(Optional.of(CustomerInquiry.builder().inquiredAt(inquiredAt).build()));
    }

    private void givenNoUnanswered() {
        given(customerInquiryRepository.findTopByMarketplaceAccount_IdAndStatusOrderByInquiredAtAsc(
                any(), any())).willReturn(Optional.empty());
    }

    /** content 가 빈 페이지 — 파싱·적재가 아니라 페이징 제어만 보는 테스트다. */
    private String page(int totalPages) {
        return "{\"data\":{\"content\":[],\"pagination\":{\"totalPages\":" + totalPages + "}}}";
    }
}
