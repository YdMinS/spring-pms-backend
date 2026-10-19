package com.pms.service.settlement;

import com.pms.dto.response.SettlementPayoutSyncResponse;

import java.time.YearMonth;

/**
 * The <b>single entry point</b> of the payment-history (payout) load (FEATURE_2609_30 / 02 · PLAN D5-2 · D11).
 *
 * <p><b>Rule</b>: controllers and schedulers call only this. Calling an adapter ({@link SettlementSource}) or
 * {@link SettlementPayoutUpserter} directly gives every caller its own copy of the fixed processing order
 * (settlementDate ASC) · per-account isolation · anchor update.
 *
 * <p>⚠️ Why it is apart from the revenue load ({@link SettlementSyncService}): the feeds differ — cycle (daily vs
 * weekly), axis (recognition-date range vs recognition month) and response shape all differ. Payouts of a platform
 * with a payout API are made only here; 11st payouts are rebuilt from the stored lines by its
 * {@link LocalPayoutBuilder}, from here and from the revenue load alike (FEATURE_2610_20 / D29 · D30).
 */
public interface SettlementPayoutSyncService {

    /**
     * Payout load + line attribution + reconciliation. A platform with a {@link LocalPayoutBuilder} (11st) is not
     * called at all: its payouts are rebuilt from the stored lines, and {@code month} does not apply
     * (FEATURE_2610_20 / D30).
     *
     * @param accountId null = every active account of a supported platform
     * @param month     null = decided per account — the first run reads {@code payoutBackfillMonths} months,
     *                  later runs the current + previous month (absorbs corrections).
     *                  🔴 When given, only that month is read and {@code lastPayoutSyncAt} (the anchor) is
     *                  <b>not updated</b> (PLAN 2609_31 D3 — a past-month backfill that used up the "first run" would
     *                  keep the initial backfill from ever running)
     * @throws IllegalArgumentException the account does not exist or a future month was requested (→ 400)
     */
    SettlementPayoutSyncResponse syncPayouts(Long accountId, YearMonth month);
}
