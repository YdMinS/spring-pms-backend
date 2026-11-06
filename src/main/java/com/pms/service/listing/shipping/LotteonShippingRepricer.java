package com.pms.service.listing.shipping;

import java.util.List;

/**
 * Re-prices the LotteON cells that use a LotteON shipping setting right after it was saved (FEATURE_2610_24 / D66 —
 * the 11st D117 · D120 · D121 · D123 and ESM D65 shape): a LotteON price reads the shipping fee policy's fee.
 *
 * <p>Each cell runs {@code ListingAssetService.recalculateOptionPrices} (MANUAL_OVERRIDE prices skipped, price history
 * reason PROPAGATION) in its own {@code REQUIRES_NEW} transaction. A cell whose re-price fails keeps its old prices
 * and is only counted. Nothing is sent to LotteON.</p>
 *
 * <p>⚠️ Call it from the controller AFTER the shipping save has returned (committed) — never inside the save
 * transaction: a new transaction cannot see an uncommitted save, and a failure would roll the save back.</p>
 */
public interface LotteonShippingRepricer {

    /** Every LotteON cell of this sales channel (its channel layer was saved). Returns the failed-cell count. */
    int repriceAccount(Long accountId);

    /** These cells, LotteON ones only (one listing override saved or cleared). Returns the failed-cell count. */
    int repriceListings(List<Long> listingIds);

    /**
     * One cell in a fresh {@code REQUIRES_NEW} transaction: re-price, then raise {@code needsMarketSync} when an
     * option carried on the market got a different price (11st D121). Proxy only.
     */
    void repriceOne(Long listingId);
}
