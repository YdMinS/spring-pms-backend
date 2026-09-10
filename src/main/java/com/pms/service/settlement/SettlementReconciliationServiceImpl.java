package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementPayout;
import com.pms.dto.response.AdjustmentView;
import com.pms.dto.response.MonthCheck;
import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.ReconLineView;
import com.pms.dto.response.ReconReportResponse;
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

/**
 * {@link SettlementReconciliationService} 구현 (FEATURE_2609_30 / 02).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional(readOnly = true)} — LAZY 연관(계정·셀·카테고리)을 타므로
 * 트랜잭션 밖에서 부르면 open-in-view=false 환경에서 LazyInitializationException 이 난다.
 *
 * <p>🔴 <b>대조하지 않은 값은 내려보내지 않는다</b> (FEATURE_2609_32 / PLAN 2609_32 D1·D2). 라인을 일부러
 * 귀속시키지 않는 유형({@code SettlementReconciler#isReconcilable} == false — 추가정산·유보금)은
 * {@code ourTotal}·{@code diff}·{@code tolerance} 를 {@code null} 로 둔다. 계산하면 0원을 "우리 집계"로
 * 내려보내 100% "차액 −전액"이 되고, 그 경고는 정보량이 0인 채로 진짜 차액을 묻어버린다.
 * 대신 참고 지표로 인식월 한 줄({@link MonthCheck})을 리포트에 싣는다(D4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementReconciliationServiceImpl implements SettlementReconciliationService {

    /**
     * 🔴 쿠팡은 차감 사유를 주지 않는다. 우리가 모르는 것을 아는 척하지 않고, 사용자가 어디에 물어야 하는지를
     * 알려준다(D13). 이 문구는 <b>서버가 소유</b>한다 — 프론트가 지어내지 않게 하려면 응답에 있어야 한다.
     */
    static final String DEDUCTION_GUIDANCE =
            "쿠팡이 사유를 제공하지 않습니다 — 플랫폼에서 확인이 필요합니다";

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
                adjustments.stream().map(SettlementReconciliationServiceImpl::view).toList(),
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
                adjustments.stream().map(SettlementReconciliationServiceImpl::view).toList(),
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
                reconcilable ? null : monthCheck(payout));
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

    private static AdjustmentView view(SettlementAdjustment adjustment) {
        String guidance = adjustment.getAdjustmentType() == SettlementAdjustmentType.DEDUCTION
                ? DEDUCTION_GUIDANCE : null;
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
