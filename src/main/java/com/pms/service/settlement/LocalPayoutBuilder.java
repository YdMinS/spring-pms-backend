package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.settlement.SettlementPayoutUpserter.PayoutUpsertResult;

/**
 * Builds a platform's payouts from settlement lines already stored here, for a market that has no payout API
 * (FEATURE_2610_20 / D29 · D30). The only implementation is {@code ElevenstPayoutBuilder} (11st groups lines by
 * remittance date).
 *
 * <p><b>Rule</b>: {@link SettlementSyncServiceImpl} calls {@link #rebuild} after every revenue load of an account of
 * this platform, and {@link SettlementPayoutSyncServiceImpl} calls it INSTEAD of
 * {@link SettlementSource#fetchPayouts} — so a platform with a builder never asks the market for payouts.</p>
 *
 * <p>⚠️ Runs inside the caller's tenant context (both callers set it per account).</p>
 */
public interface LocalPayoutBuilder {

    Platform platform();

    /** Re-groups every stored line of the account into payouts (idempotent). */
    PayoutUpsertResult rebuild(MarketplaceAccount account);
}
