package com.pms.service.coupang;

import com.pms.config.ElevenstProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.elevenst.ElevenstOrderSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 11st order sync round (FEATURE_2610_23 / D21) — hands the account to {@link ElevenstOrderSyncService}
 * (FEATURE_2610_17 / D11).
 */
@Component
@RequiredArgsConstructor
public class ElevenstOrderSyncAdapter implements OrderSyncAdapter {

    private final ElevenstOrderSyncService elevenstOrderSyncService;
    /** FEATURE_2610_17 / D10: dev · local (outbound off) scheduled rounds skip 11st channels. */
    private final ElevenstProperties elevenstProperties;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /**
     * FEATURE_2610_17 / D9 · D10: a scheduled round on a server whose 11st calls are switched off (dev · local) skips
     * the channel; a manual round still runs it and shows the 「개발 서버에서는 11번가를 부르지 않습니다」 failure (D8).
     */
    @Override
    public boolean runsInScheduledRound() {
        return elevenstProperties.isOutboundEnabled();
    }

    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        return elevenstOrderSyncService.sync(account, preset, scheduled);
    }
}
