package com.pms.service.listing.shipping;

import java.util.List;

/**
 * Re-prices the 11st cells that use a shipping setting right after that setting was saved (FEATURE_2610_13 /
 * D117 · D120 · D121 · D123). 11st cells only — a Coupang selling price does not read the shipping settings.
 *
 * <p>Each cell runs {@code ListingAssetService.recalculateOptionPrices} (MANUAL_OVERRIDE prices skipped, price
 * history reason PROPAGATION) in its own {@code REQUIRES_NEW} transaction. A cell whose re-price fails keeps its
 * old prices and is only counted. Nothing is sent to 11st (R15).</p>
 *
 * <p>⚠️ Call it from the controller AFTER the shipping save has returned (committed) — never inside the save
 * transaction: a new transaction cannot see an uncommitted save, and a failure would roll the save back.</p>
 */
public interface ElevenstShippingRepricer {

    /** Every 11st cell of this sales channel (its channel default was saved). Returns the failed-cell count. */
    int repriceAccount(Long accountId);

    /** Every 11st cell of this master (its master shipping was saved). Returns the failed-cell count. */
    int repriceMaster(Long masterId);

    /** These cells, 11st ones only (one cell saved · force-apply changed cells). Returns the failed-cell count. */
    int repriceListings(List<Long> listingIds);

    /**
     * One cell in a fresh {@code REQUIRES_NEW} transaction: re-price, then raise {@code needsMarketSync} when an
     * option carried on the market got a different selling price (D121). Invoke through the proxy only.
     */
    void repriceOne(Long listingId);
}
