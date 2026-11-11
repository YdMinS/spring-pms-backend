package com.pms.service.coupang;

import com.pms.config.LotteonProperties;
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
    /** FEATURE_2611_23 / D10 · D65: dev · local (outbound off) scheduled rounds skip LotteON channels. */
    private final LotteonProperties lotteonProperties;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /**
     * FEATURE_2611_23 / D10 · D65: dev · local (outbound off) scheduled rounds skip LotteON channels; a manual round
     * still runs it and shows the 「개발 서버에서는 롯데ON 을 부르지 않습니다」 failure.
     */
    @Override
    public boolean runsInScheduledRound() {
        return lotteonProperties.isOutboundEnabled();
    }

    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        return lotteonOrderSyncService.sync(account, preset, scheduled);
    }
}
