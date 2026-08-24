package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
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
 *   <li>standard category = the master's single {@code category} (commission lookup)</li>
 *   <li>platform code = the standard category's {@code CategoryMapping} for the cell's platform (adapter)</li>
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

    /** Standard category for this cell = its master's single {@code category}. 400 if unset (commission id use). */
    Category resolveStandardCategory(ProductListing cell);

    /**
     * Platform marketplace code for this cell = the standard category's {@link com.pms.domain.CategoryMapping}
     * for {@code cell.platform}. 400 if the standard category is unset or has no mapping for that platform
     * (adapter payload use).
     */
    String resolvePlatformCategoryCode(ProductListing cell);

    /**
     * Platform marketplace code for a (master × platform), for callers that hold the master directly (e.g.
     * the category-meta endpoint, which has master id + platform but no cell). 400 if the master has no
     * standard category or that category has no mapping for the platform. Same logic as
     * {@link #resolvePlatformCategoryCode(ProductListing)}.
     */
    String resolvePlatformCategoryCode(MasterProduct master, String platform);

    /** Delivery = option override ?? master default. 400 if both are null. */
    CarrierRate resolveDelivery(ProductListing cell, MasterProductOption masterOption);

    /** Box = option override ?? master default. 400 if both are null. */
    Package resolvePackage(ProductListing cell, MasterProductOption masterOption);
}
