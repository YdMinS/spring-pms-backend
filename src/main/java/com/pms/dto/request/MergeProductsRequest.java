package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Merge two duplicate products into one (FEATURE_2609_69 / B).
 *
 * <p>🔴 <b>This is not an automatic merge.</b> Of the fields and the history, only the values a person picked
 * ({@link MergedFields}) and the history a person ticked ({@link TransferOptions}) move to
 * {@code targetProductId}; the source is then soft-deleted. Everything runs in one transaction — a failure
 * changes nothing (PLAN D6).</p>
 *
 * <p>🔁 <b>Master links move with every merge</b> (FEATURE_2611_09 / D1 · D2 — reverses 2609_69 D6-a): the
 * source's {@code master_product_component} rows and option quantity rows are rewritten to the target, with
 * no checkbox. A master whose component set would then equal another master's is deleted and its listings move
 * to that other master (D5 · D16 · D17 · D20) — {@link #masterOptionChoices} says which option of the
 * surviving master each of its options becomes (D18 · D23 · D24).</p>
 *
 * @param targetProductId     the product that survives
 * @param sourceProductId     the product that gets soft-deleted
 * @param fields              values to overwrite on the target; a null field keeps the target's own value
 * @param transfer            which history tables to move — the screen sends every box ticked (PLAN D6-b)
 * @param masterOptionChoices one entry per option of every master the merge deletes (FEATURE_2611_09 / D23);
 *                            null = empty. A missing entry, or a target option outside the surviving master,
 *                            is a 400 (D28). Entries for other options are ignored.
 */
public record MergeProductsRequest(
        @NotNull Long targetProductId,
        @NotNull Long sourceProductId,
        @NotNull MergedFields fields,
        @NotNull TransferOptions transfer,
        List<@Valid MasterOptionChoice> masterOptionChoices
) {

    /**
     * Where one option of a deleted master goes (FEATURE_2611_09 / D23 · D24).
     *
     * @param sourceOptionId the deleted master's option
     * @param targetOptionId an option of the surviving master; wins when set
     * @param newOptionName  used only when {@code targetOptionId} is null: create an option of this name (trimmed)
     *                       on the surviving master, with the deleted option's quantities (D24 ④)
     */
    public record MasterOptionChoice(
            @NotNull Long sourceOptionId,
            Long targetOptionId,
            String newOptionName
    ) {}

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
     *
     * <p>🔴 Purchase places are not a field here (FEATURE_2609_76 / D16): they are never picked — the merge
     * always gives the target the union of both products' places.</p>
     */
    public record MergedFields(
            String productName, String brand, String barcodeId,
            BigDecimal price, String description,
            String netContent, String netContentUnit,
            Integer countQuantity, String countUnit,
            String packageHeight, String packageLength, String packageWidth,
            Long representativeImageId
    ) {}
}
