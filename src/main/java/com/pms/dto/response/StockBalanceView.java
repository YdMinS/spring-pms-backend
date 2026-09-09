package com.pms.dto.response;

/**
 * On-hand quantity of one (product × seller), DERIVED from the ledger
 * (FEATURE_2609_28 / PLAN D14, revised by PLAN 2609_29 D5).
 *
 * <p>🔴 The aggregation unit is the PAIR, not the product: stock is not shared between sellers
 * (2609_29 D4), so a product bought by two sellers produces two rows.
 *
 * <p>⚠️ {@code onHand} may be negative — that means an entry is missing, and the screen must show it
 * rather than the server hiding it.
 */
public record StockBalanceView(Long productId, String productName,
                               Long sellerId, String sellerName, long onHand) {
}
