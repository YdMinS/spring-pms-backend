package com.pms.domain;

/**
 * Physical stock movement kind (PLAN 2609_28 D6).
 *
 * <p>⚠️ Every row of this ledger is created by a HUMAN confirming that goods physically moved.
 * No sync job, scheduler or shipment hook may write one (D18) — that is the whole point of the
 * ledger existing separately from {@code purchase_record}, which records only that money was spent.
 * Money and goods move at different times (bought 10/2, arrived 10/5); deriving stock from the
 * purchase ledger would raise the balance while the warehouse is still empty.
 *
 * <p>{@link #sign()} is the direction the ledger stores. {@code ADJUST} is 0 because a stock count
 * difference can go either way and the sign comes from the quantity the user typed.
 */
public enum StockMovementType {

    /** Goods arrived and were checked in. Never tied to an order (D6). */
    STOCK_IN(+1),

    /** Goods left for a customer order — written by the outbound confirm flow (see prompt 05). */
    STOCK_OUT(-1),

    /** Returned goods physically checked back in. Requires the claim they came from. */
    RETURN_IN(+1),

    /** Damaged / expired / lost / sampled / used internally — any decrease that is not a sale. */
    DISPOSAL(-1),

    /** Stock count difference — sign comes from the quantity itself. */
    ADJUST(0);

    private final int sign;

    StockMovementType(int sign) {
        this.sign = sign;
    }

    /** +1 increases the balance, -1 decreases it, 0 = the quantity carries its own sign. */
    public int sign() {
        return sign;
    }
}
