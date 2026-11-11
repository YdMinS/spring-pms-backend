package com.pms.service.lotteon;

import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.coupang.OrderSyncPreset;

/**
 * One LotteON sales channel's sync round (FEATURE_2611_23 / D11 · D34). {@code LotteonOrderSyncAdapter} sends a
 * LotteON account here, called by {@code OrderSyncFacadeImpl} after taking the channel lock.
 *
 * <p>Order of steps: orders ([209] then [140]) → cancels ([50], R23) → return / exchange claims
 * ({@code ClaimSyncAdapter}) → inquiries ({@code InquirySyncAdapter}). Orders failing = failure, cancels failing =
 * partial (manual rounds only, both thrown on); claims / inquiries failing = log only.
 *
 * <p>❌ Do not call this from anywhere but {@code LotteonOrderSyncAdapter} — the facade sets the lock and the tenant
 * before it.
 */
public interface LotteonOrderSyncService {

    /**
     * @param scheduled true = scheduled round: failures are not recorded (2609_49 D13)
     */
    OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled);
}
