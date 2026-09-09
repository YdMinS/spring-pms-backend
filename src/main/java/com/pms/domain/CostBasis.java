package com.pms.domain;

/**
 * How a shipped line's cost was determined (FEATURE_2609_28 / PLAN D20, revised by 2609_29 D3).
 * Priority order — the first that applies wins.
 *
 * <p>The user never classifies anything: the grade falls out of what data existed when the goods
 * actually left. Cost type is a property of what happened to THIS line, not of the product.
 *
 * <p>🔴 <b>{@code PURCHASED} was removed before this enum was ever written.</b> That grade meant
 * "a purchase record is attached to this very line — zero error", and it rested on
 * {@code purchase_record.shopping_list_item_id}. FEATURE_2609_29 dropped that FK: a purchase is now
 * (product × seller), so no purchase can be attributed to one order line any more. Re-labelling a
 * seller-scoped lookup as "PURCHASED" would be worse than dropping it — the whole point of the
 * breakdown report (see {@code CostBasisBreakdown}) is to measure how much of our cost is an
 * estimate, and that number decides whether FIFO is worth building. A grade that says "exact" while
 * being an estimate makes that decision on a lie.
 */
public enum CostBasis {

    /**
     * The seller of this order line has purchase history for the product — the most recent priced
     * purchase made on or before the order date. An estimate, but grounded in money actually spent.
     */
    LATEST,

    /**
     * No usable purchase history — {@code Product.price} at snapshot time. The weakest grade:
     * a base price is what we expect to pay, not what we paid.
     */
    LISTED
}
