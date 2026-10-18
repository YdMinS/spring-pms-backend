package com.pms.dto.response;

/**
 * One contributing line shown when a product row is opened (PLAN 2609_29 D7 · D8 · FEATURE_2610_09 D17).
 *
 * <p>source: "ORDER" (order line) | "MANUAL" (manual line). {@code sellerId} · {@code sellerName} are filled on
 * both: an order line from its account, a manual line from its stored seller. A manual line keeps
 * {@code orderLineId} · {@code externalOrderId} · {@code marketplaceAccountId} · {@code platform} null — screens
 * draw it as 「{sellerName}/수동」.
 *
 * <p>🔴 A line has no purchased quantity and no purchase history (D7). Purchase records do not know orders (D3).
 */
public record PurchaseLine(
        Long itemId,
        Long orderLineId,
        String source,
        String externalOrderId,
        Long marketplaceAccountId,
        Long sellerId,
        String sellerName,
        String platform,
        int autoQty,
        int manualQty,
        int neededQty
) {}
