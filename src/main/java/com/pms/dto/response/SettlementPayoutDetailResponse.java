package com.pms.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 지급 묶음 상세 — 요약 + 조정 행 + 검증식 요약 금액 (FEATURE_2609_30 / 02 · PLAN D9).
 *
 * <p>⚠️ 금액은 전부 {@code SettlementReconciler} 가 계산한 값이다. 화면이 다시 더하면 서버와 다른 답이 나온다.
 *
 * <p>🔴 대조 불가 유형(추가정산·유보금 = {@code AMOUNT_ONLY})은 {@code ourTotal}·{@code diff} 가
 * <b>{@code null}</b> 이다 — 라인을 일부러 귀속시키지 않으므로 계산하면 항상 "차액 −전액"이 된다
 * (FEATURE_2609_32 / PLAN 2609_32 D2). {@code lineTotal}·{@code adjustmentTotal} 은 그대로 온다.
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
