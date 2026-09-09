package com.pms.domain;

/**
 * Why the price moved (FEATURE_2609_28 / PLAN D23). The reason is what makes the log answer
 * questions — "the price changed" alone explains nothing.
 *
 * <p>⚠️ Creation is not a reason. A channel add / import / option add sets a first price, which is
 * not a movement and is filtered out before it reaches the log (no previous value, DRAFT cell).
 */
public enum PriceChangeReason {

    /** Purchase amount entered -> {@code Product.price} refreshed automatically (PLAN D4 ①). */
    PURCHASE_UPDATE,

    /** Edited by hand on the product screen ({@code PATCH /api/products/{id}}). */
    PRODUCT_EDIT,

    /** Cost propagation apply -> per-option selling price recalculated (PLAN D4 ②). */
    PROPAGATION,

    /** Per-channel selling price set by hand (FEATURE_2609_19). */
    MANUAL,

    /**
     * A commission rate corrected from what the marketplace actually charged, confirmed by a person
     * on the settlement suggestion screen (FEATURE_2609_30 / PLAN D16).
     *
     * <p>⚠️ Only {@link PriceTargetType#PLATFORM_COMMISSION} rows carry this reason. It is never
     * automatic: measured ratios wobble (promotional fee discounts, mis-mapped categories, one-line
     * samples), so the log records a human decision, not a batch.
     */
    SETTLEMENT_FEEDBACK
}
