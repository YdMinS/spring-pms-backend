package com.pms.domain;

/**
 * Payout-level amount that belongs to no sale line (FEATURE_2609_30 / PLAN D8 · D13).
 *
 * <p>🔴 Never spread these across lines. Ad-cost offsets pushed into line amounts contaminate
 * per-product profitability — a common and very hard to undo mistake.
 *
 * <p>{@link #OTHER} exists because Coupang gives an amount without a reason for most deductions;
 * the report labels those "플랫폼 확인 필요" instead of guessing (D13).
 */
public enum SettlementAdjustmentType {

    DEDUCTION,
    DEBT_CARRIED,
    PENDING_RELEASE,
    OTHER
}
