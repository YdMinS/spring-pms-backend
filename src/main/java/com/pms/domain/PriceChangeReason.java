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
    MANUAL
}
