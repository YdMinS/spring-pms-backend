package com.pms.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 차이 리포트 <b>2단</b> (FEATURE_2609_30 / 02 · PLAN D12).
 *
 * <pre>
 *   blockA  왜 통장 금액이 라인 합과 다르나  → 검증식의 좌우변을 그대로 펼친다 (묶음 단위)
 *   blockB  왜 예상보다 적나                → 추정 vs 실정산을 원인 라벨로 분해한다 (라인 단위)
 * </pre>
 *
 * <p>둘을 섞지 않는 이유 = 사용자가 <b>어디에 문의할지</b> 알아야 하기 때문이다. 통장 금액 차이는 플랫폼
 * 정산팀, 라인 차이는 상품·수수료 설정 쪽이다.
 */
public record ReconReportResponse(PayoutSummary payout, BlockA blockA, BlockB blockB) {

    /**
     * 검증식 {@code Σ라인 + Σ조정 == finalAmount} 의 전개 (D9).
     *
     * <p>⚠️ 라인이 0건이면 조정만 나열된다 — <b>정상</b>이다(D5-4).
     *
     * @param unmatchedCount 미분류 라인 수. 합계에는 <b>포함</b>되어 있다(D7)
     */
    public record BlockA(BigDecimal lineTotal,
                         List<AdjustmentView> adjustments,
                         BigDecimal adjustmentTotal,
                         BigDecimal ourTotal,
                         BigDecimal finalAmount,
                         BigDecimal diff,
                         BigDecimal tolerance,
                         long unmatchedCount,
                         String reconStatus) {
    }

    /**
     * 원인 라벨 분해 (D12 상단).
     *
     * @param totalDiff {@code expected − actual}. 🔴 {@code Σ labels.amount == totalDiff} (항등식)
     */
    public record BlockB(BigDecimal expected,
                         BigDecimal actual,
                         BigDecimal totalDiff,
                         List<LabelView> labels) {
    }
}
