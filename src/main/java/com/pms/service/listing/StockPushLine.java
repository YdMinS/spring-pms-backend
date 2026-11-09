package com.pms.service.listing;

import com.pms.domain.Platform;

/**
 * What happened to one option's stock on its way to the market (FEATURE_2611_19 / D7 · D28). Built by
 * {@link ChannelStockPushService}; the stock itself is already saved whatever the result (D6).
 *
 * @param productListingId the listing
 * @param platform         the listing's market
 * @param sellerName       the listing's seller (null when the listing has none)
 * @param optionName       the channel option
 * @param result           sent · saved only · refused
 * @param message          the market's own text when {@link Result#FAILED}, else null
 */
public record StockPushLine(Long productListingId, Platform platform, String sellerName, String optionName,
                            Result result, String message) {

    /** D7 · D28: 「반영」 / 「저장만」 / 「실패」. */
    public enum Result {
        /** The market took the stock. */
        PUSHED,
        /** Saved in oclyx only — no market number yet, switched off, or the market takes it with [수정 요청] (D25). */
        SKIPPED,
        /** The market refused; the stock stays saved in oclyx (D6). */
        FAILED
    }
}
