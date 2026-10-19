package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * Payouts of one channel (account), summed (FEATURE_2609_30 / PLAN D4 · D5-4 · D5-5).
 *
 * <p>🔴 {@code pendingPayout} ("money to receive") has <b>no period filter</b> (D4). The payout axis is the
 * revenue-recognition date, which does not line up with the sale-date period — to avoid misreadings like "September
 * sales 5,000,000 / September settlement due 1,800,000", the period is not applied at all and the value is called
 * <b>"money to receive"</b>.
 *
 * <p>⚠️ {@code recon_status = AMOUNT_ONLY} (additional · reserve payouts) is included in {@code pendingPayout} too —
 * they carry no lines but are money actually to be received (D5-4 · D5-5).
 *
 * <p>🔴 <b>Do not put reconciliation status counts back in here</b> (FEATURE_2609_34). Payouts sit on the
 * recognition-date axis and are not narrowed by the sale-date period — a count that does not change with the period,
 * shown as a badge on a row with a period filter, reads as "13 mismatched this month" (it was the channel's
 * <b>whole</b> count). Reconciliation status is shown per payout by the recognition-month list
 * ({@code /payouts/by-recognition}).
 *
 * @param paidAmount amount <b>paid</b> in the period ({@code status = PAID}), cut by the confirmed payout date when
 *                   present, else {@code settlementDate} (FEATURE_2610_20 / D38 — the 11st 「지급 완료」 date).
 *                   🔴 Coupang never sends {@code finalSettlementDate} in its payment history, so Coupang rows are
 *                   cut by {@code settlementDate}. Cash basis is given per channel only — channels have different
 *                   cycles, so a seller-level sum means nothing (D4-1)
 */
public record PayoutAggregate(
        Long accountId,
        BigDecimal pendingPayout,
        BigDecimal paidAmount) {

    /** 집계 쿼리가 행을 내주지 않은 채널 = 지급 묶음이 하나도 없다. */
    public static PayoutAggregate empty(Long accountId) {
        return new PayoutAggregate(accountId, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
