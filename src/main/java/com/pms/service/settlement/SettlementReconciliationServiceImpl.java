package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.dto.response.AdjustmentView;
import com.pms.dto.response.PayoutSummary;
import com.pms.dto.response.ReconLineView;
import com.pms.dto.response.ReconReportResponse;
import com.pms.dto.response.SettlementPayoutDetailResponse;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link SettlementReconciliationService} 구현 (FEATURE_2609_30 / 02).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional(readOnly = true)} — LAZY 연관(계정·셀·카테고리)을 타므로
 * 트랜잭션 밖에서 부르면 open-in-view=false 환경에서 LazyInitializationException 이 난다.
 */
@Service
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
    public SettlementPayoutDetailResponse payout(Long payoutId) {
        SettlementPayout payout = require(payoutId);
        List<SettlementLine> lines = lines(payout);
        List<SettlementAdjustment> adjustments =
                settlementAdjustmentRepository.findBySettlementPayout_Id(payoutId);

        BigDecimal lineTotal = settlementReconciler.lineTotal(lines);
        BigDecimal adjustmentTotal = settlementReconciler.adjustmentTotal(adjustments);
        return new SettlementPayoutDetailResponse(
                summary(payout, lines.size()),
                adjustments.stream().map(SettlementReconciliationServiceImpl::view).toList(),
                lineTotal,
                adjustmentTotal,
                lineTotal.add(adjustmentTotal),
                settlementReconciler.diff(payout.getFinalAmount(), lines, adjustments),
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

        BigDecimal lineTotal = settlementReconciler.lineTotal(lines);
        BigDecimal adjustmentTotal = settlementReconciler.adjustmentTotal(adjustments);
        ReconReportResponse.BlockA blockA = new ReconReportResponse.BlockA(
                lineTotal,
                adjustments.stream().map(SettlementReconciliationServiceImpl::view).toList(),
                adjustmentTotal,
                lineTotal.add(adjustmentTotal),
                payout.getFinalAmount(),
                settlementReconciler.diff(payout.getFinalAmount(), lines, adjustments),
                settlementReconciler.tolerance(lines.size()),
                unmatchedCount(lines),
                payout.getReconStatus() == null ? null : payout.getReconStatus().name());

        SettlementDiffAnalyzer.DiffReport diff = settlementDiffAnalyzer.analyze(payout, lines);
        return new ReconReportResponse(summary(payout, lines.size()), blockA,
                new ReconReportResponse.BlockB(diff.expected(), diff.actual(), diff.totalDiff(), diff.labels()));
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
