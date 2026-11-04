package com.pms.dto.response;

import java.util.List;

/**
 * What a merge would do to the masters, read before [병합하기] (FEATURE_2611_09 / D28). Nothing is written.
 *
 * <p>Only the masters the merge deletes are listed: a master whose component set would equal another master's
 * once the discarded product is replaced by the kept one (D5 · D16). Every other master of the discarded product
 * simply has that component replaced (D1 · D3) and needs no choice.</p>
 *
 * @param pairs one entry per deleted master, ascending by its id
 */
public record MergePreviewResponse(List<MasterPair> pairs) {

    /**
     * A master the merge deletes and the master its listings move to.
     *
     * @param deleteMasterId   the master that is deleted (D20)
     * @param keepMasterId     the master that already holds the kept product with the same component set (D16)
     * @param listings         every listing of the deleted master, on a market or not (D17), ascending by id
     * @param keepOptions      every option of the surviving master, ascending by id (D24 ①)
     * @param optionRows       one row per option of the deleted master, ascending by id (D23)
     */
    public record MasterPair(Long deleteMasterId, String deleteMasterName,
                             Long keepMasterId, String keepMasterName,
                             List<ListingRef> listings,
                             List<OptionRef> keepOptions,
                             List<OptionRow> optionRows) {}

    /** A listing (「판매상품」) that moves. */
    public record ListingRef(Long listingId, String listingName, String platform) {}

    /** One option with its quantities, ascending by product id. */
    public record OptionRef(Long optionId, String name, List<Item> items) {}

    /** One (product, quantity) of an option. */
    public record Item(Long productId, String productName, Integer quantity) {}

    /**
     * One option of the deleted master.
     *
     * @param items           its quantities with the discarded product already replaced by the kept one — the
     *                        quantities a new option would get (D24 ④)
     * @param defaultOptionId the first choice (D24 ②): the surviving option with the same quantities and the same
     *                        name, else the one with the same quantities and the lowest id; null = no option has
     *                        the same quantities, so the first choice is 「새 옵션 만들기」 — the only case where
     *                        a new option may be chosen (D24 ③)
     */
    public record OptionRow(Long sourceOptionId, String sourceOptionName, List<Item> items,
                            Long defaultOptionId) {}
}
