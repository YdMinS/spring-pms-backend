package com.pms.service.settlement;

import com.pms.domain.SaleType;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증식 — 허용오차 · 라인 0건 · PENDING · AMOUNT_ONLY · 환불 부호 (PLAN D9 · D5-4 · D5-5).
 */
class SettlementReconcilerTest {

    private final SettlementReconciler reconciler = new SettlementReconciler(BigDecimal.ONE);

    @Test
    void reconciledWithinTolerance() {
        // 라인 1건 → 허용오차 = max(10, 1×1) = 10원. 차액 7원은 절사 오차 범위다.
        SettlementPayout payout = payout(SettlementType.WEEKLY, "100000");
        List<SettlementLine> lines = List.of(line(SaleType.SALE, "100007"));

        assertThat(reconciler.evaluate(payout, lines, List.of()))
                .isEqualTo(SettlementReconStatus.RECONCILED);
    }

    @Test
    void unreconciledOutsideTolerance() {
        SettlementPayout payout = payout(SettlementType.WEEKLY, "100000");
        List<SettlementLine> lines = List.of(line(SaleType.SALE, "103000"));

        assertThat(reconciler.evaluate(payout, lines, List.of()))
                .isEqualTo(SettlementReconStatus.UNRECONCILED);
        assertThat(reconciler.diff(payout.getFinalAmount(), lines, List.of()))
                .isEqualByComparingTo("3000");
    }

    @Test
    void payoutWithNoLinesIsReconciledFromAdjustmentsAlone() {
        // 유보금 해제·채무 상환은 판매 라인이 없다(D5-4). 라인 수로 나누는 계산이 있으면 여기서 터진다.
        SettlementPayout payout = payout(SettlementType.MONTHLY, "-85000");
        List<SettlementAdjustment> adjustments =
                List.of(adjustment(SettlementAdjustmentType.DEDUCTION, "85000"));

        assertThat(reconciler.evaluate(payout, List.of(), adjustments))
                .isEqualTo(SettlementReconStatus.RECONCILED);
    }

    @Test
    void pendingWhenFinalAmountMissing() {
        SettlementPayout payout = payout(SettlementType.WEEKLY, null);

        assertThat(reconciler.evaluate(payout, List.of(line(SaleType.SALE, "1000")), List.of()))
                .isEqualTo(SettlementReconStatus.PENDING);
    }

    @Test
    void additionalPayoutIsAmountOnly() {
        // 대조할 라인이 애초에 없다 — UNRECONCILED 로 찍으면 정상 입금이 매번 경고가 된다(D5-5).
        SettlementPayout payout = payout(SettlementType.ADDITIONAL, "12000");

        assertThat(reconciler.evaluate(payout, List.of(), List.of()))
                .isEqualTo(SettlementReconStatus.AMOUNT_ONLY);
        assertThat(reconciler.isReconcilable(SettlementType.ADDITIONAL)).isFalse();
        assertThat(reconciler.isReconcilable(SettlementType.RESERVE)).isFalse();
    }

    @Test
    void refundLineCountsNegative() {
        BigDecimal total = reconciler.lineTotal(List.of(
                line(SaleType.SALE, "10000"), line(SaleType.REFUND, "4000")));

        assertThat(total).isEqualByComparingTo("6000");
    }

    @Test
    void pendingReleaseIsExcludedFromTheSum() {
        // "앞으로" 정산에 포함될 금액이라 이번 지급액이 아니다 — 넣으면 검증식이 항상 안 맞는다.
        BigDecimal total = reconciler.adjustmentTotal(List.of(
                adjustment(SettlementAdjustmentType.DEDUCTION, "85000"),
                adjustment(SettlementAdjustmentType.DEBT_CARRIED, "12000"),
                adjustment(SettlementAdjustmentType.PENDING_RELEASE, "500000"),
                adjustment(SettlementAdjustmentType.OTHER, "7000")));

        assertThat(total).isEqualByComparingTo("-97000");
    }

    @Test
    void toleranceScalesWithLineCountAndKeepsFloor() {
        assertThat(reconciler.tolerance(0)).isEqualByComparingTo("10");
        assertThat(reconciler.tolerance(250)).isEqualByComparingTo("250");
    }

    private static SettlementPayout payout(SettlementType type, String finalAmount) {
        return SettlementPayout.builder()
                .id(1L)
                .settlementType(type)
                .finalAmount(finalAmount == null ? null : new BigDecimal(finalAmount))
                .build();
    }

    private static SettlementLine line(SaleType saleType, String settlementAmount) {
        return SettlementLine.builder()
                .saleType(saleType)
                .settlementAmount(new BigDecimal(settlementAmount))
                .build();
    }

    private static SettlementAdjustment adjustment(SettlementAdjustmentType type, String amount) {
        return SettlementAdjustment.builder().adjustmentType(type).amount(new BigDecimal(amount)).build();
    }
}
