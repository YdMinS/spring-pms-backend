package com.pms.domain;

/**
 * Reconciliation verdict of a payout unit (FEATURE_2609_30 / PLAN D9 · D5-5).
 *
 * <p>🔴 Written by prompt 02 only. Prompt 01 loads lines and never reconciles, so every payout row
 * starts at {@link #PENDING}.
 *
 * <p>{@link #AMOUNT_ONLY} = ADDITIONAL/RESERVE payouts: Coupang never tells us which orders they
 * cover, so we record the amount and deliberately do NOT attach sale lines. Attaching them would
 * double-count revenue that the WEEKLY/MONTHLY payout already claimed.
 */
public enum SettlementReconStatus {

    PENDING,
    RECONCILED,
    UNRECONCILED,
    AMOUNT_ONLY
}
