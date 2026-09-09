package com.pms.domain;

/**
 * Which price moved (FEATURE_2609_28 / PLAN D23).
 *
 * <p>The two axes are deliberately kept in ONE table: a margin question ("when did this cell get
 * thin") is answered by reading a cost row and a selling row side by side, and two tables would
 * make that a union query forever.
 *
 * <p>⚠️ Exactly one reference column is filled per row — {@code product_id} for
 * {@link #PRODUCT_COST}, {@code product_listing_option_id} for {@link #LISTING_SELLING}, and
 * <b>neither</b> for {@link #PLATFORM_COMMISSION}. The DB cannot express that rule;
 * {@code PriceHistoryRecorder} is the only writer and enforces it.
 */
public enum PriceTargetType {

    /** {@code Product.price} — the purchase cost baseline. Tenant-wide: it has no seller axis. */
    PRODUCT_COST,

    /** {@code ProductListingOption.sellingPrice} — the price of one option on one channel. */
    LISTING_SELLING,

    /**
     * {@code PlatformCategory.commissionRate} — the commission the price engine reverse-calcs with
     * (FEATURE_2609_30 / PLAN D16). Not a price of ours, but it moves every future selling price
     * through {@code PriceCalculator}, so it belongs in the same log: "the margin got thin" and
     * "the commission was corrected on that day" are read side by side.
     *
     * <p>⚠️ The category itself is NOT identified on the row — {@code price_change_log} has no
     * {@code platform_category_id} column and this piece adds no changeset. A row therefore says
     * "a commission moved from x to y", and the <b>which</b> is answered by the settlement
     * suggestion screen that produced it. Widening the log is a separate, deliberate decision.
     */
    PLATFORM_COMMISSION
}
