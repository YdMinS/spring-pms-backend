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

    /**
     * 🔴 <b>대조할 근거가 없는 것은 "금액 차이"가 아니다.</b> 매출내역(라인)을 아직 적재하지 못하면
     * {@code ourTotal} 이 0 이라 검증식이 자동으로 "차액 = −전액"을 내놓는다. UNRECONCILED 로 찍으면
     * 정상 입금 전건이 경고가 된다(prod 실측: 주/월 정산 23건 전건이 그렇게 찍혔다).
     *
     * <p>{@code OTHER} 는 합산에서 빠지므로(의미 미확인 금액) 조정 행이 있어도 근거가 되지 않는다.
     */
    @Test
    void payoutWithoutAnyComparableBasisIsPendingNotUnreconciled() {
        SettlementPayout payout = payout(SettlementType.MONTHLY, "3369281");

        assertThat(reconciler.evaluate(payout, List.of(), List.of()))
                .isEqualTo(SettlementReconStatus.PENDING);
        assertThat(reconciler.evaluate(payout, List.of(),
                List.of(adjustment(SettlementAdjustmentType.OTHER, "6903562"))))
                .isEqualTo(SettlementReconStatus.PENDING);
    }

    /** 라인이 붙은 뒤에도 금액이 어긋나면 그때는 진짜 UNRECONCILED 다 — 위 규칙이 경고를 삼키면 안 된다. */
    @Test
    void basisPresentAndAmountOffStaysUnreconciled() {
        SettlementPayout payout = payout(SettlementType.MONTHLY, "3369281");

        assertThat(reconciler.evaluate(payout, List.of(line(SaleType.SALE, "3000000")), List.of()))
                .isEqualTo(SettlementReconStatus.UNRECONCILED);
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
