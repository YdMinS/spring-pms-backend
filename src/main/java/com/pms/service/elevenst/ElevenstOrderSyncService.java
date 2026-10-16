package com.pms.service.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.coupang.OrderSyncPreset;

/**
 * One 11st sales channel's sync round (FEATURE_2610_17 / D9 · D11 · D29). {@code OrderSyncFacadeImpl} sends an 11st
 * account here after taking the channel lock — the Coupang steps and their order stay untouched (D11).
 *
 * <p>Order of steps: orders → cancel claims → return / exchange claims ({@code ClaimSyncAdapter}) → inquiries
 * ({@code InquirySyncAdapter}). The status-record rules are the Coupang ones: orders failing = failure (manual
 * rounds only), cancels failing = partial, claims / inquiries failing = log only.
 *
 * <p>❌ Do not call this from anywhere but the facade — the lock and the tenant are set there.
 */
public interface ElevenstOrderSyncService {

    /**
     * @param scheduled true = scheduled round: failures and partial results are not recorded (2609_49 D13)
     */
    OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled);
}
