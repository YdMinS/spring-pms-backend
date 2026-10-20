package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;

/**
 * Per-marketplace order sync round (FEATURE_2610_23 / D21 — one frame per function, the same shape as
 * {@code ClaimSyncAdapter}). Implementations: {@link CoupangOrderSyncAdapter} · {@link ElevenstOrderSyncAdapter}.
 *
 * <p>Callers pick the one adapter whose {@link #platform()} matches the account. A marketplace without an adapter
 * is not a sync target (D14) — the scheduled and the seller / all rounds skip it, and the screen's target list does
 * not show it.
 *
 * <p>⚠️ The lock and the tenant are the facade's job ({@link OrderSyncFacadeImpl}) — an adapter runs inside both.
 */
public interface OrderSyncAdapter {

    /** The value compared with {@code marketplace_account.platform}. */
    Platform platform();

    /**
     * Whether the scheduled rounds run this marketplace. {@code false} skips the channel in a scheduled round without
     * calling or recording anything; a manual round still runs it (FEATURE_2610_17 / D8 · D10).
     */
    default boolean runsInScheduledRound() {
        return true;
    }

    /**
     * One account's round, already under the lock and the account's tenant.
     *
     * @param scheduled whether this is a scheduled round — a scheduled round records no failure or partial failure
     *                  (FEATURE_2609_49 / D13)
     */
    OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled);
}
