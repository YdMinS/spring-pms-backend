package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * This month's payouts summed across sellers and channels (FEATURE_2610_29 D26 · D30) — the dashboard payout card.
 *
 * <p>A payout belongs to the month of its confirmed payout date, else its scheduled payout date (KST).
 *
 * @param total     {@code Σ finalAmount} of this month's payouts — 「이번 달 지급 예정 총액」
 * @param paid      the part with {@code status = PAID} — 「지금까지 지급된 금액」
 * @param remaining {@code total − paid} — 「남은 지급 예정 금액」
 */
public record SettlementMonthSummaryResponse(BigDecimal total, BigDecimal paid, BigDecimal remaining) {
}
