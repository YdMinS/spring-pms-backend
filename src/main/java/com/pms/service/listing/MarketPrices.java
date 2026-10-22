package com.pms.service.listing;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Prices read from the market for one listing, already paired with our options (FEATURE_2610_27 / D16 · D30 · D37).
 * Built by {@link ListingChannel#readMarketPrices}, applied by {@link MarketPriceApplier}.
 *
 * <p>An option missing from {@link #byOptionId()} had no pair on the market and is left untouched (D16). A price that
 * is null or ≤ 0 is kept in the map as it was read — the applier keeps that option's previous value (D36).</p>
 *
 * @param byOptionId our {@code ProductListingOption.id} → the prices read for it
 */
public record MarketPrices(Map<Long, OptionPrice> byOptionId) {

    /**
     * @param price        Coupang · 11st price, or the Gmarket price of an ESM option
     * @param auctionPrice the Auction price of an ESM option; null elsewhere
     */
    public record OptionPrice(BigDecimal price, BigDecimal auctionPrice) {
    }
}
