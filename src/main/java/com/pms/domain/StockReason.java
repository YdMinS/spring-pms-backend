package com.pms.domain;

import java.util.Set;

/**
 * Why a physical stock movement happened (PLAN 2609_28 D7).
 *
 * <p>A code, not free text — "top N disposal causes" has to be countable.
 *
 * <p>Each value carries the {@link StockMovementType}s it may appear under, so the combination check
 * stays a single {@link #allowedFor(StockMovementType)} call instead of string comparisons scattered
 * across the service. Adding a reason means touching this file only.
 *
 * <p>⚠️ {@code SAMPLE} and {@code INTERNAL_USE} sit under {@code DISPOSAL} on purpose: from the
 * ledger's point of view they are identical — a decrease that is not a sale. What separates them is
 * where the money lands (loss vs marketing / internal cost), and that is exactly what this
 * {@code reason} column answers later. Giving them their own movement type would split the balance
 * arithmetic for no gain.
 */
public enum StockReason {

    /** Bought from a supplier — the unit price is inherited from {@code purchase_record} (D8). */
    PURCHASE(StockMovementType.STOCK_IN),

    /** Goods already in the warehouse when the ledger started (D10). Unit price = Product.price snapshot. */
    OPENING(StockMovementType.STOCK_IN),

    /** Received for free (sample, promotion) — unit price forced to 0 (D8). */
    FREE(StockMovementType.STOCK_IN),

    DAMAGED(StockMovementType.DISPOSAL),
    EXPIRED(StockMovementType.DISPOSAL),
    LOST(StockMovementType.DISPOSAL),
    SAMPLE(StockMovementType.DISPOSAL),
    INTERNAL_USE(StockMovementType.DISPOSAL),

    /** Stock count difference. */
    COUNT_DIFF(StockMovementType.ADJUST),

    /** Anything else — requires {@code reasonNote} so the row is still explainable. */
    ETC(StockMovementType.STOCK_IN, StockMovementType.DISPOSAL, StockMovementType.ADJUST);

    private final Set<StockMovementType> types;

    StockReason(StockMovementType... types) {
        this.types = Set.of(types);
    }

    /** Whether this reason may be used with the given movement type. */
    public boolean allowedFor(StockMovementType type) {
        return types.contains(type);
    }
}
