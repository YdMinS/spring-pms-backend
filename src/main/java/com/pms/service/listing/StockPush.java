package com.pms.service.listing;

import com.pms.domain.ProductListingOption;

/**
 * One option's stock on its way to the market (FEATURE_2611_19 / D5 · D16 · D19 · D20).
 *
 * @param option the channel option (already saved)
 * @param stock  the market stock to send = {@link ListingStockPolicy#resolve} (channel ?? master ?? 9999, 0 = sold out)
 */
public record StockPush(ProductListingOption option, int stock) {
}
