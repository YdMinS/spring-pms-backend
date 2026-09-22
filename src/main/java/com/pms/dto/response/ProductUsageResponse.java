package com.pms.dto.response;

import java.util.List;

/**
 * Where a single product is currently used (FEATURE_2609_69 / A).
 *
 * <p>Links block deletion and are <b>never migrated automatically</b> (PLAN D6-a) — the operator has to
 * unlink them by hand, the same rule that makes a master require its options to be removed first:</p>
 * <ul>
 *   <li>{@code masterProducts} — {@code master_product_component}, the real master ↔ product mapping.
 *       Unlinked on the master product screen.</li>
 *   <li>{@code listingOptions} — 그 마스터를 쓰는 채널 옵션들. 🔴 2609_71 이후 <b>끊는 곳은 마스터 한
 *       군데</b>다: 셀 구성품 사본이 사라져 이 목록은 마스터 옵션 items 를 FK 로 따라 내려온 파급 범위이며,
 *       비어 있지 않으면 {@code masterProducts} 도 반드시 비어 있지 않다.</li>
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
 * @param listingOptions  channel listing options composed of this product (resolved through the master),
 *                        each carrying its owning cell id so the screen can link straight to that cell
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

    /**
     * A channel listing option that contains this product.
     *
     * @param id        the listing <b>option</b> id — not addressable by any screen on its own
     * @param listingId the cell (판매 상품) that owns the option. 🔴 이 값이 있어야 화면이 판매 상품 상세로
     *                  바로 보낼 수 있다 — 없던 시절에는 목록으로만 보낼 수 있었다(2026-09-23)
     * @param quantity  마스터 옵션이 정한 수량(2609_71) — 채널마다 다른 수량은 더 이상 존재하지 않는다
     */
    public record ListingOptionRef(Long id, String name, Long listingId, String listingName,
                                   Long marketplaceAccountId, String accountAlias,
                                   String platform, Integer quantity, String status) {}

    /** Row counts of the six history tables that reference a product. */
    public record HistoryCounts(long stockMovements, long purchaseRecords, long shipmentItems,
                                long images, long shoppingListItems, long priceChangeLogs) {}
}
