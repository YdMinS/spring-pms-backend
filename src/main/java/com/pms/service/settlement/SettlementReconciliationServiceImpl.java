package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.dto.response.AdjustmentView;
import com.pms.dto.response.MonthCheck;
import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.ReconLineView;
import com.pms.dto.response.ReconReportResponse;
import com.pms.dto.response.SaleMonthSettlement;
import com.pms.dto.response.SettlementPayoutDetailResponse;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * {@link SettlementReconciliationService} implementation (FEATURE_2609_30 / 02).
 *
 * <p>⚠️ Class-level {@code @Transactional(readOnly = true)} — it walks LAZY associations (account · cell · category),
 * so a call outside a transaction throws LazyInitializationException with open-in-view=false.
 *
 * <p>🔴 <b>Values we did not compare are not sent</b> (FEATURE_2609_32 / PLAN 2609_32 D1 · D2). Types whose lines are
 * deliberately not attributed ({@code SettlementReconciler#isReconcilable} == false — additional payouts · reserve)
 * get {@code ourTotal} · {@code diff} · {@code tolerance} = {@code null}. Computing them would send 0 won as "our
 * total", a 100% "diff −full amount", and that zero-information warning buries the real differences.
 * Instead the report carries one recognition-month reference row ({@link MonthCheck}) (D4) — except for 11st payouts
 * ({@code NO_TOTAL}), whose totals are our own sums, so there is no platform total to compare with
 * (FEATURE_2610_20 / D13).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementReconciliationServiceImpl implements SettlementReconciliationService {

    /**
     * 🔴 The marketplace gives no reason for a deduction. We do not pretend to know it — we tell the user where to
     * ask (D13). The text is <b>owned by the server</b>: the screen must not invent it, so it has to be in the
     * response. The subject is the payout channel's market name (FEATURE_2610_20 / D14); an unknown platform reads
     * 「마켓이」.
     */
    static final String DEDUCTION_GUIDANCE = "%s 사유를 제공하지 않습니다 — 플랫폼에서 확인이 필요합니다";

    /** Market name + subject particle for {@link #DEDUCTION_GUIDANCE} (FEATURE_2610_20 / D14). */
    private static final Map<Platform, String> MARKET_SUBJECTS = Map.of(
            Platform.COUPANG, "쿠팡이",
            Platform.NAVER, "네이버가",
            Platform.ELEVENST, "11번가가");

    private final SettlementPayoutRepository settlementPayoutRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementReconciler settlementReconciler;
    private final SettlementDiffAnalyzer settlementDiffAnalyzer;
    private final SettlementReportExporter settlementReportExporter;

    @Override
    public List<PayoutSummary> payouts(Long sellerId, Long accountId, LocalDate from, LocalDate to) {
        return settlementPayoutRepository.search(sellerId, accountId, from, to).stream()
                .map(payout -> summary(payout,
                        settlementLineRepository.countBySettlementPayout_Id(payout.getId())))
                .toList();
    }

    @Override
    public List<PayoutSummary> payoutsByRecognitionMonth(Long sellerId, Long accountId,
                                                         LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("조회 기간(from, to)을 모두 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        // 'YYYY-MM' 고정폭이라 사전식 비교 = 연월 비교다(repository Javadoc 참조).
        return settlementPayoutRepository.searchByRecognitionMonth(sellerId, accountId,
                        YearMonth.from(from).toString(), YearMonth.from(to).toString()).stream()
                .map(payout -> summary(payout,
                        settlementLineRepository.countBySettlementPayout_Id(payout.getId())))
                .toList();
    }

    @Override
    public List<SaleMonthSettlement> bySaleMonth(Long accountId, LocalDate from, LocalDate to) {
        if (accountId == null || from == null || to == null) {
            throw new IllegalArgumentException("채널(accountId)과 조회 기간(from, to)을 모두 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        return settlementLineRepository.aggregateBySaleMonth(accountId, from, to).stream()
                .map(SettlementReconciliationServiceImpl::toSaleMonth)
                .toList();
    }

    /** 🔴 월 문자열 조립은 여기 한 곳에서만 한다(쿼리는 year/month 정수 — H2↔MySQL 이식성). */
    private static SaleMonthSettlement toSaleMonth(Object[] row) {
        YearMonth month = YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        return new SaleMonthSettlement(
                month.toString(),
                (LocalDate) row[2],
                row[3] == SettlementPayoutStatus.PAID,
                ((Number) row[4]).longValue(),
                (BigDecimal) row[5],
                (BigDecimal) row[6]);
    }

    @Override
    public SettlementPayoutDetailResponse payout(Long payoutId) {
        SettlementPayout payout = require(payoutId);
        List<SettlementLine> lines = lines(payout);
        List<SettlementAdjustment> adjustments =
                settlementAdjustmentRepository.findBySettlementPayout_Id(payoutId);

        boolean reconcilable = settlementReconciler.isReconcilable(payout.getSettlementType());
        BigDecimal lineTotal = settlementReconciler.lineTotal(lines);
        BigDecimal adjustmentTotal = settlementReconciler.adjustmentTotal(adjustments);
        return new SettlementPayoutDetailResponse(
                summary(payout, lines.size()),
                adjustments.stream().map(adjustment -> view(adjustment, payout)).toList(),
                lineTotal,
                adjustmentTotal,
                reconcilable ? lineTotal.add(adjustmentTotal) : null,
                reconcilable ? settlementReconciler.diff(payout.getFinalAmount(), lines, adjustments) : null,
                unmatchedCount(lines));
    }

    @Override
    public List<ReconLineView> lines(Long payoutId, String label, Boolean unmatched) {
        SettlementPayout payout = require(payoutId);
        return settlementDiffAnalyzer.analyze(payout, lines(payout)).lines().stream()
                .filter(line -> label == null || label.equalsIgnoreCase(line.label()))
                .filter(line -> unmatched == null || unmatched == line.unmatched())
                .toList();
    }

    @Override
    public ReconReportResponse report(Long payoutId) {
        SettlementPayout payout = require(payoutId);
        List<SettlementLine> lines = lines(payout);
        List<SettlementAdjustment> adjustments =
                settlementAdjustmentRepository.findBySettlementPayout_Id(payoutId);

        boolean reconcilable = settlementReconciler.isReconcilable(payout.getSettlementType());
        BigDecimal lineTotal = settlementReconciler.lineTotal(lines);
        BigDecimal adjustmentTotal = settlementReconciler.adjustmentTotal(adjustments);
        ReconReportResponse.BlockA blockA = new ReconReportResponse.BlockA(
                lineTotal,
                adjustments.stream().map(adjustment -> view(adjustment, payout)).toList(),
                adjustmentTotal,
                reconcilable ? lineTotal.add(adjustmentTotal) : null,
                payout.getFinalAmount(),
                reconcilable ? settlementReconciler.diff(payout.getFinalAmount(), lines, adjustments) : null,
                reconcilable ? settlementReconciler.tolerance(lines.size()) : null,
                unmatchedCount(lines),
                payout.getReconStatus() == null ? null : payout.getReconStatus().name());

        SettlementDiffAnalyzer.DiffReport diff = settlementDiffAnalyzer.analyze(payout, lines);
        return new ReconReportResponse(summary(payout, lines.size()), blockA,
                new ReconReportResponse.BlockB(diff.expected(), diff.actual(), diff.totalDiff(), diff.labels()),
                reconcilable || payout.getReconStatus() == SettlementReconStatus.NO_TOTAL
                        ? null : monthCheck(payout));
    }

    /**
     * 인식월 참고 대조 한 줄 (PLAN 2609_32 D4·D4-1·D6·D8).
     *
     * <p>🔴 {@code ourLineCount == 0} 이어도 {@code diff} 를 그대로 담는다. 서버가 {@code null} 로 지우면
     * "계산 못 했다"와 "그 달 매출내역을 아직 안 불러왔다"가 다시 뭉개진다 — 숨기는 판단은 화면이
     * {@code ourLineCount} 로 한다(D4-1).
     *
     * <p>🔴 인식월을 못 읽으면 예외를 던지지 않고 {@code null} 을 돌려준다. 참고 지표 하나 때문에 상세
     * 화면 전체가 500 이 되면 안 된다.
     */
    private MonthCheck monthCheck(SettlementPayout payout) {
        String month = payout.getRevenueRecognitionMonth();
        Long accountId = payout.getMarketplaceAccount() == null ? null : payout.getMarketplaceAccount().getId();
        YearMonth yearMonth = null;
        if (month != null && accountId != null) {
            try {
                yearMonth = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                yearMonth = null;
            }
        }
        if (yearMonth == null) {
            log.debug("monthCheck skipped: payout={} month={}", payout.getId(), month);
            return null;
        }

        BigDecimal ourLineTotal = settlementLineRepository.sumSignedSettlementAmount(
                accountId, yearMonth.atDay(1), yearMonth.atEndOfMonth(), SaleType.REFUND);
        long ourLineCount = settlementLineRepository
                .countByMarketplaceAccount_IdAndRecognitionDateBetween(
                        accountId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        BigDecimal payoutTotal = settlementPayoutRepository.sumFinalAmountByMonth(accountId, month);
        return new MonthCheck(month,
                ourLineTotal,
                ourLineCount,
                payoutTotal,
                ourLineTotal.subtract(payoutTotal),
                settlementPayoutRepository
                        .countByMarketplaceAccount_IdAndRevenueRecognitionMonth(accountId, month),
                settlementPayoutRepository
                        .countByMarketplaceAccount_IdAndRevenueRecognitionMonthAndFinalAmountIsNull(
                                accountId, month));
    }

    @Override
    public byte[] export(Long payoutId) {
        SettlementPayout payout = require(payoutId);
        return settlementReportExporter.toXlsx(settlementDiffAnalyzer.analyze(payout, lines(payout)).lines());
    }

    private List<SettlementLine> lines(SettlementPayout payout) {
        return settlementLineRepository
                .findBySettlementPayout_IdOrderByRecognitionDateAscIdAsc(payout.getId());
    }

    private SettlementPayout require(Long payoutId) {
        return settlementPayoutRepository.findWithAccountById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("지급 묶음을 찾을 수 없습니다: " + payoutId));
    }

    private static long unmatchedCount(List<SettlementLine> lines) {
        return lines.stream().filter(line -> line.getOrderLine() == null).count();
    }

    private static AdjustmentView view(SettlementAdjustment adjustment, SettlementPayout payout) {
        MarketplaceAccount account = payout.getMarketplaceAccount();
        String subject = account == null || account.getPlatform() == null
                ? null : MARKET_SUBJECTS.get(account.getPlatform());
        String guidance = adjustment.getAdjustmentType() == SettlementAdjustmentType.DEDUCTION
                ? String.format(DEDUCTION_GUIDANCE, subject == null ? "마켓이" : subject) : null;
        return new AdjustmentView(adjustment.getAdjustmentType().name(), adjustment.getAmount(),
                adjustment.getNote(), guidance);
    }

    private static PayoutSummary summary(SettlementPayout payout, long lineCount) {
        MarketplaceAccount account = payout.getMarketplaceAccount();
        Seller seller = account == null ? null : account.getSeller();
        return new PayoutSummary(
                payout.getId(),
                account == null ? null : account.getId(),
                account == null || account.getPlatform() == null ? null : account.getPlatform().name(),
                account == null ? null : account.getAccountAlias(),
                seller == null ? null : seller.getId(),
                seller == null ? null : seller.getSellerName(),
                payout.getSettlementType() == null ? null : payout.getSettlementType().name(),
                payout.getRevenueRecognitionMonth(),
                payout.getRecognitionFrom(),
                payout.getRecognitionTo(),
                payout.getSettlementDate(),
                payout.getFinalSettlementDate(),
                payout.getTotalSale(),
                payout.getServiceFee(),
                payout.getFinalAmount(),
                payout.getStatus() == null ? null : payout.getStatus().name(),
                payout.getReconStatus() == null ? null : payout.getReconStatus().name(),
                lineCount);
    }
}
