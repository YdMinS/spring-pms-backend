package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * On-hand quantity of one (product × seller), DERIVED from the ledger
 * (FEATURE_2609_28 / PLAN D14, revised by PLAN 2609_29 D5).
 *
 * <p>🔴 The aggregation unit is the PAIR, not the product: stock is not shared between sellers
 * (2609_29 D4), so a product bought by two sellers produces two rows.
 *
 * <p>⚠️ {@code onHand} may be negative — that means an entry is missing, and the screen must show it
 * rather than the server hiding it.
 *
 * @param imageUrl the product's representative photo ({@code product.image_url}) as stored; null = none
 *                 (FEATURE_2611_02 D8)
 * @param amount   purchase-price value of what is on hand (FEATURE_2611_02 D23 · D30), 2 decimals; null = 「미기입」 —
 *                 a unit price could not be found (D24) or {@code onHand} is negative (D25). Never a selling price
 */
public record StockBalanceView(Long productId, String productName, String imageUrl,
                               Long sellerId, String sellerName, long onHand, BigDecimal amount) {

    /** The ledger sum before any amount is known — the shape {@code StockMovementRepository.findBalances} selects. */
    public StockBalanceView(Long productId, String productName, String imageUrl,
                            Long sellerId, String sellerName, long onHand) {
        this(productId, productName, imageUrl, sellerId, sellerName, onHand, null);
    }

    /** The same row with its purchase-price amount (FEATURE_2611_02 D28). */
    public StockBalanceView withAmount(BigDecimal value) {
        return new StockBalanceView(productId, productName, imageUrl, sellerId, sellerName, onHand, value);
    }
}
