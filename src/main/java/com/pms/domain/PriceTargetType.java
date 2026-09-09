package com.pms.domain;

/**
 * Which price moved (FEATURE_2609_28 / PLAN D23).
 *
 * <p>The two axes are deliberately kept in ONE table: a margin question ("when did this cell get
 * thin") is answered by reading a cost row and a selling row side by side, and two tables would
 * make that a union query forever.
 *
 * <p>⚠️ Exactly one reference column is filled per row — {@code product_id} for
 * {@link #PRODUCT_COST}, {@code product_listing_option_id} for {@link #LISTING_SELLING}. The DB
 * cannot express that rule; {@code PriceHistoryRecorder} is the only writer and enforces it.
 */
public enum PriceTargetType {

    /** {@code Product.price} — the purchase cost baseline. Tenant-wide: it has no seller axis. */
    PRODUCT_COST,

    /** {@code ProductListingOption.sellingPrice} — the price of one option on one channel. */
    LISTING_SELLING
}
