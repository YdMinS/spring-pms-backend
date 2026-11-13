package com.pms.service.listing;

import com.pms.domain.ProductListingOption;

import java.util.List;

/**
 * Pairs an option read from the market with the channel cell's option (FEATURE_2611_27). The one place of this rule —
 * the market photo copy ({@code ListingMarketSyncServiceImpl}) and the market values of an import
 * ({@code ListingMarketValueServiceImpl}) both use it.
 *
 * <p>Rule: the market option id first ({@code platformOptionId}), then the option name; the first match wins.</p>
 */
public final class MarketOptionMatch {

    private MarketOptionMatch() {
    }

    /**
     * @param options          the channel cell's options
     * @param marketOptionId   the market's option id (Coupang {@code vendorItemId} · LotteON {@code sitmNo}); may be null
     * @param marketOptionName the market's option name; may be null
     * @return the first option with the same {@code platformOptionId}, else the first with the same
     *         {@code optionName}, else {@code null}
     */
    public static ProductListingOption find(List<ProductListingOption> options, String marketOptionId,
                                            String marketOptionName) {
        if (marketOptionId != null) {
            for (ProductListingOption option : options) {
                if (marketOptionId.equals(option.getPlatformOptionId())) {
                    return option;
                }
            }
        }
        if (marketOptionName != null) {
            for (ProductListingOption option : options) {
                if (marketOptionName.equals(option.getOptionName())) {
                    return option;
                }
            }
        }
        return null;
    }
}
