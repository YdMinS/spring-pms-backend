package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.lotteon.LotteonOrderSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LotteON order sync round (FEATURE_2611_23 / D5 · D11) — hands the account to {@link LotteonOrderSyncService}.
 */
@Component
@RequiredArgsConstructor
public class LotteonOrderSyncAdapter implements OrderSyncAdapter {

    private final LotteonOrderSyncService lotteonOrderSyncService;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /**
     * FEATURE_2611_23 / D65: scheduled rounds skip LotteON channels until the cancel sync lands (cancels are not read
     * yet); a manual [동기화] still runs the channel.
     */
    @Override
    public boolean runsInScheduledRound() {
        return false;
    }

    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        return lotteonOrderSyncService.sync(account, preset, scheduled);
    }
}
