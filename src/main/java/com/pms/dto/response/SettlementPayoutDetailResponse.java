package com.pms.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 지급 묶음 상세 — 요약 + 조정 행 + 검증식 요약 금액 (FEATURE_2609_30 / 02 · PLAN D9).
 *
 * <p>⚠️ 금액은 전부 {@code SettlementReconciler} 가 계산한 값이다. 화면이 다시 더하면 서버와 다른 답이 나온다.
 */
public record SettlementPayoutDetailResponse(
        PayoutSummary payout,
        List<AdjustmentView> adjustments,
        BigDecimal lineTotal,
        BigDecimal adjustmentTotal,
        BigDecimal ourTotal,
        BigDecimal diff,
        long unmatchedCount) {
}
