package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.CarrierRate;
import com.pms.domain.ProductListing;

/**
 * Channel-config resolver (FEATURE_2608_06 / 13) — the shared seam that derives a channel cell's category,
 * delivery and box from the <b>master/option</b> level instead of re-reading them off the (now deprecated,
 * nullable) {@link ProductListing} columns. Used by both the price engine ({@link PriceCalculator}) and the
 * marketplace adapters (e.g. {@code CoupangListingAdapter}).
 *
 * <p>Resolution rules:</p>
 * <ul>
 *   <li>category = master × platform ({@code MasterProductCategory})</li>
 *   <li>delivery = option override ?? master default delivery</li>
 *   <li>box      = option override ?? master default package</li>
 * </ul>
 *
 * <p>⚠️ This resolver is the <b>single owner of the null checks</b>: a missing config (no category / neither
 * override nor master default) throws {@link IllegalArgumentException} (→ 400), and so does a bad
 * CarrierRate/Package whose {@code getCost()} is null. Callers use {@code .getCost()} without their own null
 * guard.</p>
 *
 * <p>⚠️ LazyInitialization: the cell's {@code masterProduct} (+ its default delivery/package) and the option
 * overrides are all LAZY, and {@code open-in-view=false}. Callers must invoke this inside a
 * {@code @Transactional} boundary (they already do: PriceCalculator via {@code regenerateAssets}, and the
 * adapter via {@code ListingRegistrationService}).</p>
 */
public interface MasterChannelConfigService {

    /** Category for this cell = master × cell.platform. 400 if the master has no category for that platform. */
    Category resolveCategory(ProductListing cell);

    /** Category for (masterProductId, platform); pre-validation seam for channel-add (no cell yet). 400 if unset. */
    Category resolveCategory(Long masterProductId, String platform);

    /** Delivery = option override ?? master default. 400 if both are null. */
    CarrierRate resolveDelivery(ProductListing cell, MasterProductOption masterOption);

    /** Box = option override ?? master default. 400 if both are null. */
    Package resolvePackage(ProductListing cell, MasterProductOption masterOption);
}
