package com.pms.dto.response;

/**
 * On-hand quantity of one product, DERIVED from the ledger (FEATURE_2609_28 / PLAN D14).
 *
 * <p>⚠️ {@code onHand} may be negative — that means an entry is missing, and the screen must show it
 * rather than the server hiding it.
 */
public record StockBalanceView(Long productId, String productName, long onHand) {
}
