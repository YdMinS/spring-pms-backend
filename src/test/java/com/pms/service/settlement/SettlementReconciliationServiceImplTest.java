package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.dto.response.ReconReportResponse;
import com.pms.dto.response.SettlementPayoutDetailResponse;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 대조하지 않은 값은 내려보내지 않는다 + 인식월 참고 대조 (FEATURE_2609_32 / PLAN 2609_32 D1·D2·D4·D4-1).
 *
 * <p>🔴 이 클래스가 지키는 것은 두 가지다. ① 라인을 일부러 안 붙이는 유형은 {@code ourTotal}·{@code diff}·
 * {@code tolerance} 가 {@code null} 이어야 하고 ② 주정산은 <b>지금까지처럼</b> 값이 나와야 한다 —
 * ②가 깨지면 진짜 차액이 통째로 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementReconciliationServiceImplTest {

    private static final long PAYOUT_ID = 11L;
    private static final long ACCOUNT_ID = 7L;
    private static final String MONTH = "2026-08";

    @Mock private SettlementPayoutRepository settlementPayoutRepository;
    @Mock private SettlementAdjustmentRepository settlementAdjustmentRepository;
    @Mock private SettlementLineRepository settlementLineRepository;
    @Mock private SettlementDiffAnalyzer settlementDiffAnalyzer;
    @Mock private SettlementReportExporter settlementReportExporter;

    /** 🔴 목이 아니라 진짜 객체다 — 판정({@code isReconcilable})까지 같이 검증해야 D1 이 지켜진다. */
    @Spy private SettlementReconciler settlementReconciler = new SettlementReconciler(BigDecimal.ONE);

    @InjectMocks private SettlementReconciliationServiceImpl service;

    @Test
    void additionalPayoutReportOmitsOurTotalAndDiff() {
        givenPayout(SettlementType.ADDITIONAL, "350000", null);
        givenLines();
        givenEmptyDiffReport();

        ReconReportResponse.BlockA blockA = service.report(PAYOUT_ID).blockA();

        assertThat(blockA.ourTotal()).isNull();
        assertThat(blockA.diff()).isNull();
        assertThat(blockA.tolerance()).isNull();
        assertThat(blockA.finalAmount()).isEqualByComparingTo("350000");
        assertThat(blockA.lineTotal()).isEqualByComparingTo("0");
    }

    @Test
    void additionalPayoutReportCarriesMonthCheck() {
        givenPayout(SettlementType.ADDITIONAL, "350000", MONTH);
        givenLines();
        givenEmptyDiffReport();
        givenMonthAggregates("4000000", 120, "3650000", 3, 1);

        var monthCheck = service.report(PAYOUT_ID).monthCheck();

        assertThat(monthCheck).isNotNull();
        assertThat(monthCheck.revenueRecognitionMonth()).isEqualTo(MONTH);
        assertThat(monthCheck.diff()).isEqualByComparingTo("350000");
        assertThat(monthCheck.ourLineCount()).isEqualTo(120);
        assertThat(monthCheck.payoutCount()).isEqualTo(3);
        assertThat(monthCheck.pendingPayoutCount()).isEqualTo(1);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(settlementLineRepository)
                .sumSignedSettlementAmount(eq(ACCOUNT_ID), from.capture(), to.capture(), eq(SaleType.REFUND));
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(to.getValue()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    /** 🔴 D4-1 — 라인 0건은 "차이가 전액"이 아니라 미적재다. 그래도 서버는 diff 를 숨기지 않는다. */
    @Test
    void monthCheckKeepsDiffWhenNoLinesLoaded() {
        givenPayout(SettlementType.ADDITIONAL, "350000", MONTH);
        givenLines();
        givenEmptyDiffReport();
        givenMonthAggregates("0", 0, "3650000", 3, 1);

        var monthCheck = service.report(PAYOUT_ID).monthCheck();

        assertThat(monthCheck.ourLineCount()).isZero();
        assertThat(monthCheck.diff()).isEqualByComparingTo("-3650000");
    }

    /** 🔴 회귀 방지의 핵심 — 주정산은 지금까지처럼 검증식을 그대로 내려보낸다(D5). */
    @Test
    void weeklyPayoutKeepsDiffAndHasNoMonthCheck() {
        givenPayout(SettlementType.WEEKLY, "880000", MONTH);
        givenLines(line(SaleType.SALE, "880000"));
        givenEmptyDiffReport();

        ReconReportResponse report = service.report(PAYOUT_ID);

        assertThat(report.blockA().diff()).isEqualByComparingTo("0");
        assertThat(report.blockA().ourTotal()).isEqualByComparingTo("880000");
        assertThat(report.blockA().tolerance()).isNotNull();
        assertThat(report.monthCheck()).isNull();
        verify(settlementLineRepository, never())
                .sumSignedSettlementAmount(anyLong(), any(), any(), any());
    }

    @Test
    void payoutDetailOmitsOurTotalForAmountOnly() {
        givenPayout(SettlementType.RESERVE, "120000", MONTH);
        givenLines(line(SaleType.SALE, "50000"));

        SettlementPayoutDetailResponse detail = service.payout(PAYOUT_ID);

        assertThat(detail.ourTotal()).isNull();
        assertThat(detail.diff()).isNull();
        assertThat(detail.lineTotal()).isEqualByComparingTo("50000");
        assertThat(detail.adjustmentTotal()).isEqualByComparingTo("0");
    }

    /** 인식월을 못 읽으면 참고 지표만 빠진다 — 상세 화면 전체가 500 이 되면 안 된다. */
    @Test
    void unparsableMonthLeavesMonthCheckNull() {
        givenPayout(SettlementType.ADDITIONAL, "350000", null);
        givenLines();
        givenEmptyDiffReport();

        assertThat(service.report(PAYOUT_ID).monthCheck()).isNull();
        verify(settlementLineRepository, never())
                .sumSignedSettlementAmount(anyLong(), any(), any(), any());
        verify(settlementPayoutRepository, never()).sumFinalAmountByMonth(anyLong(), any());
    }

    // ------------------------------------------------------------- fixtures

    private void givenPayout(SettlementType type, String finalAmount, String month) {
        MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder()
                .id(ACCOUNT_ID).accountAlias("쿠팡-메인").build();
        SettlementPayout payout = SettlementPayout.builder()
                .id(PAYOUT_ID)
                .marketplaceAccount(account)
                .settlementType(type)
                .revenueRecognitionMonth(month)
                .settlementDate(LocalDate.of(2026, 9, 1))
                .finalAmount(new BigDecimal(finalAmount))
                .reconStatus(SettlementReconStatus.AMOUNT_ONLY)
                .build();
        given(settlementPayoutRepository.findWithAccountById(PAYOUT_ID)).willReturn(Optional.of(payout));
        given(settlementAdjustmentRepository.findBySettlementPayout_Id(PAYOUT_ID)).willReturn(List.of());
    }

    private void givenLines(SettlementLine... lines) {
        given(settlementLineRepository.findBySettlementPayout_IdOrderByRecognitionDateAscIdAsc(PAYOUT_ID))
                .willReturn(List.of(lines));
    }

    private void givenEmptyDiffReport() {
        given(settlementDiffAnalyzer.analyze(any(), any())).willReturn(new SettlementDiffAnalyzer.DiffReport(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of()));
    }

    private void givenMonthAggregates(String lineTotal, long lineCount, String payoutTotal,
                                      long payoutCount, long pendingCount) {
        given(settlementLineRepository.sumSignedSettlementAmount(
                eq(ACCOUNT_ID), any(), any(), eq(SaleType.REFUND))).willReturn(new BigDecimal(lineTotal));
        given(settlementLineRepository.countByMarketplaceAccount_IdAndRecognitionDateBetween(
                eq(ACCOUNT_ID), any(), any())).willReturn(lineCount);
        given(settlementPayoutRepository.sumFinalAmountByMonth(ACCOUNT_ID, MONTH))
                .willReturn(new BigDecimal(payoutTotal));
        given(settlementPayoutRepository.countByMarketplaceAccount_IdAndRevenueRecognitionMonth(
                ACCOUNT_ID, MONTH)).willReturn(payoutCount);
        given(settlementPayoutRepository
                .countByMarketplaceAccount_IdAndRevenueRecognitionMonthAndFinalAmountIsNull(
                        ACCOUNT_ID, MONTH)).willReturn(pendingCount);
    }

    private static SettlementLine line(SaleType saleType, String settlementAmount) {
        return SettlementLine.builder()
                .saleType(saleType)
                .settlementAmount(new BigDecimal(settlementAmount))
                .recognitionDate(LocalDate.of(2026, 8, 10))
                .build();
    }
}
