package com.pms.dto.response;

import java.util.List;

/**
 * Where a single product is currently used (FEATURE_2609_69 / A).
 *
 * <p>Two kinds of links block deletion and are <b>never migrated automatically</b> (PLAN D6-a) — the
 * operator has to unlink them by hand, the same rule that makes a master require its options to be
 * removed first:</p>
 * <ul>
 *   <li>{@code masterProducts} — {@code master_product_component}, the real master ↔ product mapping.
 *       Unlinked on the master product screen.</li>
 *   <li>{@code listingOptions} — {@code product_listing_product}, the legacy channel-cell composition.
 *       Unlinked on the listing (cell) screen; clearing the master link does <b>not</b> release it
 *       (PLAN D11).</li>
 * </ul>
 *
 * <p>{@code master_product_option_item} is not an independent mapping — it is the quantity vector over
 * the master's component set — so it rides along under its master as {@code optionQuantities} and never
 * affects {@code deletable} (PLAN D2).</p>
 *
 * <p>{@code history} counts are informational only. History never blocks deletion: if it did, nothing
 * that was ever bought, moved or shipped could be deleted (PLAN D3).</p>
 *
 * @param productId       the inspected product
 * @param masterProducts  masters composed of this product
 * @param listingOptions  channel listing options composed of this product
 * @param history         per-table row counts of past records
 * @param deletable       true only when both link lists are empty
 * @param blockers        Korean noun phrases naming what blocks deletion and where to unlink it; the
 *                        closing clause is owned by {@link com.pms.exception.ProductInUseException}
 */
public record ProductUsageResponse(
        Long productId,
        List<MasterProductRef> masterProducts,
        List<ListingOptionRef> listingOptions,
        HistoryCounts history,
        boolean deletable,
        List<String> blockers
) {

    /** A master that contains this product, with that master's per-option quantities for reference. */
    public record MasterProductRef(Long id, String name, List<OptionQty> optionQuantities) {}

    /** One entry of a master option's quantity vector for this product. */
    public record OptionQty(Long optionId, String optionName, Integer quantity) {}

    /** A channel listing option that contains this product. */
    public record ListingOptionRef(Long id, String name, Long marketplaceAccountId, String accountAlias,
                                   String platform, Integer quantity, String status) {}

    /** Row counts of the six history tables that reference a product. */
    public record HistoryCounts(long stockMovements, long purchaseRecords, long shipmentItems,
                                long images, long shoppingListItems, long priceChangeLogs) {}
}
