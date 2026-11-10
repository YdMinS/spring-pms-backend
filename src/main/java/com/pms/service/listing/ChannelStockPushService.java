package com.pms.service.listing;

import java.util.List;
import java.util.Map;

/**
 * Sends saved channel option stocks to the market (FEATURE_2611_19 / D1 · D26) — the one place both entries use: the
 * 「채널별 재고」 save ({@link ListingOptionService#setOptionStocks}) and the master option save
 * ({@code MasterProductService.updateOption}, D10 · D27).
 *
 * <p>🔴 Call it <b>after</b> the stock save has committed. A refusal never undoes the save (D6) and never touches the
 * listing's 「마켓 반영 필요」 flag (D31) — it only comes back as a {@link StockPushLine.Result#FAILED} line.</p>
 *
 * <p>Per listing: a platform without a stock push gives no line at all (D7 「그 밖의 플랫폼」); a switched-off option is
 * {@code SKIPPED} (D25); an option the market cannot take yet (no market number — {@link ListingChannel#stockPushTargets})
 * is {@code SKIPPED}; the account is looked up once, and only when something is sent (D21). The value sent =
 * {@link ListingStockPolicy#resolve} on the saved option.</p>
 */
public interface ChannelStockPushService {

    /**
     * @param optionIdsByListingId the options whose stock was just saved, per listing (insertion order kept)
     * @return one line per given option of a listing whose market takes a stock push, in listing → stored option order
     */
    List<StockPushLine> push(Map<Long, List<Long>> optionIdsByListingId);
}
