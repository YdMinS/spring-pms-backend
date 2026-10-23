package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * Payout sums of one date range (FEATURE_2610_29 D26 · D30) — the {@code SettlementPayoutRepository.sumByPayoutDate}
 * projection.
 *
 * @param total {@code Σ finalAmount} of the payouts in the range
 * @param paid  {@code Σ finalAmount} of those with {@code status = PAID}
 */
public record PayoutMonthTotals(BigDecimal total, BigDecimal paid) {
}
