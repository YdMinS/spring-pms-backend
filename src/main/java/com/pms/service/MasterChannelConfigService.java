package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.CarrierRate;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
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

    /**
     * The category this cell actually uses, plus whether that category is the <b>channel's own</b>
     * (2609_45/D10). One return type on purpose: if each consumer re-derived {@code own} the judgement would
     * drift (the payload could send the channel category while the attribute merge still assumed the master's).
     *
     * @param category the resolved marketplace category node (never null — the resolver throws instead)
     * @param own      2609_45/D10-1: the resolved code <b>differs from the master's</b> resolved code. NOT
     *                 "the cell has a code": the import stores {@code platformCategoryCode} whether or not it
     *                 matches, so code-presence would mark every legacy imported cell as channel-owned.
     */
    record ChannelCategory(PlatformCategory category, boolean own) {
    }

    /** Standard category for this cell = its master's single {@code category}. 400 if unset (commission id use). */
    Category resolveStandardCategory(ProductListing cell);

    /**
     * 2609_45/D9·D10 — the single promotion point: a cell that carries its own marketplace category code keeps
     * that category (payload, commission, attribute/notice schema), otherwise it follows the master.
     *
     * <p>Fallback to the master (with {@code own=false}) when the code is absent, when no
     * {@link PlatformCategory} row exists for it, or when that row has no commission rate (D11 — without a
     * commission the selling-price reverse-calc is a 400, so we would break pricing to honour the category).</p>
     *
     * <p>400 conditions are those of {@link #resolvePlatformCategory(ProductListing)} <b>only when the master
     * is the one being used</b>: a cell with a usable own category survives a master with no category/mapping.</p>
     */
    ChannelCategory resolveChannelCategory(ProductListing cell);

    /**
     * Marketplace category node ({@link PlatformCategory}) that owns the mall code + commission for this cell
     * (FEATURE_2608_06 / 52). Since 2609_45/D10 this is {@code resolveChannelCategory(cell).category()} — the
     * channel's own category when it has one, else the standard category's
     * {@link com.pms.domain.CategoryMapping} for {@code cell.platform} → its linked {@code platformCategory} FK.
     * 400 when the master is used and the standard category is unset, there is no mapping for that platform,
     * or the mapping is not yet linked to a PlatformCategory.
     */
    PlatformCategory resolvePlatformCategory(ProductListing cell);

    /**
     * Platform marketplace code for this cell = {@code resolvePlatformCategory(cell).getCode()} (adapter
     * payload use). 400 conditions as {@link #resolvePlatformCategory(ProductListing)}.
     */
    String resolvePlatformCategoryCode(ProductListing cell);

    /**
     * Platform marketplace code for a (master × platform), for callers that hold the master directly (e.g.
     * the category-meta endpoint, which has master id + platform but no cell). 400 if the master has no
     * standard category or that category has no mapping for the platform. Same logic as
     * {@link #resolvePlatformCategoryCode(ProductListing)}.
     */
    String resolvePlatformCategoryCode(MasterProduct master, Platform platform);

    /**
     * Platform marketplace code for a (standard category × platform), for callers that hold only a category id
     * (FEATURE_2608_06 / 57 — the category-scoped meta schema lookup, which needs a schema <b>before</b> a
     * master exists). 400 if the category id does not exist, or the category has no mapping for the platform.
     * Same core resolution as {@link #resolvePlatformCategoryCode(ProductListing)}.
     */
    String resolvePlatformCategoryCode(Long categoryId, Platform platform);

    /** Delivery = option override ?? master default. 400 if both are null. */
    CarrierRate resolveDelivery(ProductListing cell, MasterProductOption masterOption);

    /**
     * Box = option override ?? master default. 400 if both are null.
     *
     * <p>🔴 Also 400 when the resolved box is {@link com.pms.domain.BoxKind#RECYCLED} (PLAN 2609_40 D21):
     * a recycled box costs 0 and pricing off it would give the goods away. This resolves ONE box that was
     * already assigned — it is not a list, so it takes no kind filter; the list API does the filtering.</p>
     */
    Package resolvePackage(ProductListing cell, MasterProductOption masterOption);
}
