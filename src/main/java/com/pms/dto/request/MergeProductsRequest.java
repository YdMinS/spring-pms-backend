package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Merge two duplicate products into one (FEATURE_2609_69 / B).
 *
 * <p>🔴 <b>This is not an automatic merge.</b> Only the values a person picked ({@link MergedFields}) and
 * the history a person ticked ({@link TransferOptions}) move to {@code targetProductId}; the source is then
 * soft-deleted. Everything runs in one transaction — a failure changes nothing (PLAN D6).</p>
 *
 * <p>🔴 <b>Links are never migrated</b> (PLAN D6-a): {@code master_product_component} rows stay where they
 * are. If the source still carries one, the merge is refused with 409 and the operator unlinks it on the
 * master product screen first — 2609_71 이후 끊는 곳은 마스터 한 군데다.</p>
 *
 * @param targetProductId the product that survives
 * @param sourceProductId the product that gets soft-deleted
 * @param fields          values to overwrite on the target; a null field keeps the target's own value
 * @param transfer        which history tables to move — the screen sends every box ticked (PLAN D6-b)
 */
public record MergeProductsRequest(
        @NotNull Long targetProductId,
        @NotNull Long sourceProductId,
        @NotNull MergedFields fields,
        @NotNull TransferOptions transfer
) {

    /**
     * Per-table opt-in for history migration. An unticked table is left on the source and is buried with it
     * when the source is soft-deleted — nothing is deleted, it just stops being visible (PLAN D6-b).
     *
     * <p>{@code box_recipe} is deliberately absent: those rows carry the source id inside a sorted string
     * key, cannot be rewritten without colliding with the target's own key, and rebuild themselves every
     * time a parcel is packed — so they are always deleted, with no checkbox (PLAN D12).</p>
     *
     * @param appendMemo append one migration note line to the target's description (PLAN D6-c)
     */
    public record TransferOptions(
            boolean purchaseRecords, boolean stockMovements, boolean shipmentItems,
            boolean images, boolean shoppingListItems, boolean priceChangeLogs,
            boolean appendMemo
    ) {}

    /**
     * Partial overwrite of the target's own fields — <b>a null field keeps the target value</b>, so nothing
     * of the source leaks in by itself (PLAN D9).
     *
     * <p>🔴 {@code representativeImageId} is <b>not a flag column</b>: {@code product_image} has no
     * representative column (PLAN D13). It means "put that image at gallery position 0 and point
     * {@code Product.imageUrl} at it" — the rule {@code ProductImageService} already uses. If it names a
     * <b>source</b> image while {@code transfer.images()} is off, the request is a 400: that image is not
     * migrated, so it would be buried with the source and the representative would point at nothing.</p>
     */
    public record MergedFields(
            String productName, String brand, String barcodeId, String store,
            BigDecimal price, String description,
            String netContent, String netContentUnit,
            String packageHeight, String packageLength, String packageWidth,
            Long representativeImageId
    ) {}
}
