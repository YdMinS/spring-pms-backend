package com.pms.service.price;

import com.pms.dto.response.ListingMarginResponse;

import java.math.BigDecimal;

/**
 * Margin view of one listing and the [가격 설정] margin preview (FEATURE_2610_27 / D1 · D6 · D8 · D10 · D20). Read
 * only: no market call, nothing stored.
 */
public interface ListingMarginService {

    /**
     * Every option's margin at {@code marketPrice ?? sellingPrice} (D2), ESM options per site (D3).
     *
     * @throws com.pms.exception.ResourceNotFoundException (→404) when the listing is not in this tenant
     */
    ListingMarginResponse margins(Long listingId);

    /**
     * The margin of a price typed in the [가격 설정] window (D6 · D20). A margin that cannot be calculated is returned
     * with its reason, not thrown (D38).
     *
     * @param auction true = judge it as the Auction price of an ESM option
     * @throws com.pms.exception.ResourceNotFoundException (→404) when the listing is not in this tenant
     * @throws IllegalArgumentException (→400) when the option is not this listing's, or {@code auction} on a non-ESM
     *         listing
     */
    ListingMarginResponse.SiteMargin preview(Long listingId, Long optionId, BigDecimal price, boolean auction);
}
