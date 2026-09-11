package com.pms.service.settlement;

import com.pms.domain.SaleType;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 검증식 {@code Σ라인 + Σ조정 == finalAmount} 의 <b>단일 소유자</b> (FEATURE_2609_30 / PLAN D9 · D5-5).
 *
 * <p><b>필수 규칙</b>: 대사 판정과 합계는 여기만 계산한다. 적재(→ {@link SettlementPayoutUpserter})와 조회
 * 리포트(→ {@code SettlementReconciliationServiceImpl})가 각자 더하면 화면과 저장값이 서로 다른 답을 내고,
 * 그 순간 대사 화면 전체가 신뢰를 잃는다.
 *
 * <p><b>부호 규칙</b> — 응답 금액은 전부 양수로 저장돼 있고 부호는 타입이 결정한다:
 * <pre>
 *   SALE            +settlementAmount
 *   REFUND          −settlementAmount
 *   DEDUCTION       −amount        차감
 *   DEBT_CARRIED    −amount        전주 채무 상환
 *   PENDING_RELEASE  0             🔴 정보성(다음 정산 예정액) — 합산에서 제외한다
 *   OTHER            0             의미 미확인 — 합산에서 제외하고 note 로만 보여준다
 * </pre>
 *
 * <p>🔴 <b>ADDITIONAL·RESERVE 는 채점하지 않는다</b>({@link SettlementReconStatus#AMOUNT_ONLY}). 쿠팡이
 * "어느 주문이 이 지급에 들어갔는지"를 알려주지 않아 대조할 라인이 애초에 없다 — UNRECONCILED 로 찍으면
 * 정상 입금이 매번 경고로 뜬다.
 *
 * <p>🔴 <b>라인 0건은 정상</b>이다(D5-4). 유보금 해제·전주 채무 상환·광고비 정산은 판매 라인이 없고,
 * 검증식은 {@code Σ라인(0) + Σ조정 == finalAmount} 로 성립한다 — 라인 수로 나누는 계산을 넣지 말 것.
 *
 * <p><b>사용 예</b>:
 * <pre>
 * BigDecimal ourTotal = reconciler.ourTotal(lines, adjustments);
 * SettlementReconStatus status = reconciler.evaluate(payout, lines, adjustments);
 * </pre>
 */
@Component
public class SettlementReconciler {

    /** 허용오차의 하한(원). 라인이 0건이어도 절사 오차 한 자리는 인정한다. */
    static final BigDecimal MIN_TOLERANCE = BigDecimal.TEN;

    /** 라인 귀속·검증식을 적용하는 유형. 나머지는 금액만 기록한다(D5-5). */
    private static final List<SettlementType> RECONCILABLE_TYPES =
            List.of(SettlementType.WEEKLY, SettlementType.MONTHLY);

    private final BigDecimal tolerancePerLine;

    public SettlementReconciler(
            @Value("${oclyx.settlement.recon-tolerance-per-line:1}") BigDecimal tolerancePerLine) {
        this.tolerancePerLine = tolerancePerLine == null ? BigDecimal.ONE : tolerancePerLine;
    }

    /** 라인 귀속·검증식 대상인가 (D5-5). ADDITIONAL·RESERVE·DAILY·UNKNOWN 은 false. */
    public boolean isReconcilable(SettlementType type) {
        return type != null && RECONCILABLE_TYPES.contains(type);
    }

    /**
     * 대사 판정.
     *
     * <ul>
     *   <li>귀속 대상이 아닌 유형 → {@code AMOUNT_ONLY}</li>
     *   <li>{@code finalAmount} 미수신 → {@code PENDING} (🔴 아직 채점할 답안지가 없다 — UNRECONCILED 아님)</li>
     *   <li>우리 쪽 근거가 통째로 없음(라인 0건 + 합산 조정 0원) → {@code PENDING} (아래 참조)</li>
     *   <li>|우리 계산 − finalAmount| ≤ 허용오차 → {@code RECONCILED}, 아니면 {@code UNRECONCILED}</li>
     * </ul>
     *
     * <p>🔴 <b>대조할 근거가 없는 것은 "금액 차이"가 아니다.</b> 매출내역(라인)을 아직 적재하지 못한 지급 묶음은
     * {@code ourTotal} 이 0 이라 검증식이 자동으로 "차액 = −전액"을 내놓는다. 그것을 {@code UNRECONCILED} 로
     * 찍으면 <b>정상 입금 전건이 매번 경고</b>가 되고(실측: 주/월 정산 23건 전건), 진짜 차액이 그 안에 묻힌다.
     * 위의 {@code finalAmount} 미수신과 방향만 반대인 같은 결손이라 같은 값({@code PENDING})으로 둔다.
     *
     * <p>⚠️ 라인 0건이어도 <b>조정으로 금액이 설명되는</b> 묶음(유보금 해제·전주 채무 상환)은 그대로 채점한다
     * (D5-4) — 그래서 조건이 "라인 0건"이 아니라 "라인 0건 <b>그리고</b> 합산 대상 조정 0원"이다.
     * {@code finalAmount} 가 0 인 묶음도 채점 대상이다(0 == 0 은 실제로 맞은 것이다).
     */
    public SettlementReconStatus evaluate(SettlementPayout payout, Collection<SettlementLine> lines,
                                          Collection<SettlementAdjustment> adjustments) {
        if (!isReconcilable(payout.getSettlementType())) {
            return SettlementReconStatus.AMOUNT_ONLY;
        }
        if (payout.getFinalAmount() == null) {
            return SettlementReconStatus.PENDING;
        }
        if ((lines == null || lines.isEmpty())
                && adjustmentTotal(adjustments).signum() == 0
                && payout.getFinalAmount().signum() != 0) {
            return SettlementReconStatus.PENDING;
        }
        BigDecimal diff = diff(payout.getFinalAmount(), lines, adjustments);
        return diff.abs().compareTo(tolerance(lines == null ? 0 : lines.size())) <= 0
                ? SettlementReconStatus.RECONCILED
                : SettlementReconStatus.UNRECONCILED;
    }

    /**
     * 쿠팡 finalAmount − 우리 계산. 🔴 <b>통장 기준</b>이다: {@code +} 면 우리가 <b>더 받았고</b>,
     * {@code −} 면 덜 받았다.
     *
     * <p>라인 단위 차액({@code SettlementDiffAnalyzer})도 같은 부호 규칙을 쓴다 — 한 화면에 두 부호가
     * 섞이면 사용자가 매번 어느 쪽 기준인지 다시 물어야 한다.
     */
    public BigDecimal diff(BigDecimal finalAmount, Collection<SettlementLine> lines,
                           Collection<SettlementAdjustment> adjustments) {
        BigDecimal ours = ourTotal(lines, adjustments);
        return finalAmount == null ? ours.negate() : finalAmount.subtract(ours);
    }

    public BigDecimal ourTotal(Collection<SettlementLine> lines, Collection<SettlementAdjustment> adjustments) {
        return lineTotal(lines).add(adjustmentTotal(adjustments));
    }

    /** Σ 라인 정산액 — REFUND 는 음수로 반영한다. */
    public BigDecimal lineTotal(Collection<SettlementLine> lines) {
        if (lines == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SettlementLine line : lines) {
            total = total.add(signedAmount(line));
        }
        return total;
    }

    /** Σ 조정 — PENDING_RELEASE·OTHER 는 0 으로 빠진다(위 부호 규칙). */
    public BigDecimal adjustmentTotal(Collection<SettlementAdjustment> adjustments) {
        if (adjustments == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SettlementAdjustment adjustment : adjustments) {
            total = total.add(signedAmount(adjustment));
        }
        return total;
    }

    /** 라인 1건의 부호 적용 금액. */
    public BigDecimal signedAmount(SettlementLine line) {
        BigDecimal amount = line.getSettlementAmount() == null ? BigDecimal.ZERO : line.getSettlementAmount();
        return line.getSaleType() == SaleType.REFUND ? amount.negate() : amount;
    }

    /**
     * 조정 1건의 부호 적용 금액.
     *
     * <p>🔴 {@code PENDING_RELEASE} 는 "보류 해제 후 <b>앞으로</b> 정산에 포함될 금액"이라 이번 지급액이
     * 아니다 — 합산에 넣으면 검증식이 항상 안 맞는다. {@code OTHER} 는 의미를 모르는 금액이라 같은 이유로 뺀다.
     */
    public BigDecimal signedAmount(SettlementAdjustment adjustment) {
        BigDecimal amount = adjustment.getAmount() == null ? BigDecimal.ZERO : adjustment.getAmount();
        SettlementAdjustmentType type = adjustment.getAdjustmentType();
        if (type == SettlementAdjustmentType.DEDUCTION || type == SettlementAdjustmentType.DEBT_CARRIED) {
            return amount.negate();
        }
        return BigDecimal.ZERO;
    }

    /**
     * 허용오차 = max(10원, 라인당 허용오차 × 라인 수).
     *
     * <p>절사 오차는 라인마다 쌓이므로 고정 10원은 라인이 많은 묶음에서 곧바로 오탐이 된다. 반대로 라인이
     * 0건이어도 하한 10원은 남는다(D5-4 — 라인 수로 나누지 않는다).
     */
    public BigDecimal tolerance(int lineCount) {
        BigDecimal scaled = tolerancePerLine.multiply(BigDecimal.valueOf(Math.max(0, lineCount)));
        return scaled.compareTo(MIN_TOLERANCE) > 0 ? scaled : MIN_TOLERANCE;
    }
}
