package com.pms.service.listing;

import com.pms.domain.ListingStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product as it currently exists on the marketplace, read back through {@link ListingChannel#fetchProduct}
 * (FEATURE_2609_22 / D8). Read-only projection — the import service turns it into a channel cell.
 *
 * <p>⚠️ Every field is nullable on purpose: only {@code statusName}/{@code items[]}/{@code itemName}/
 * {@code vendorItemId}/{@code sellerProductItemId} are confirmed against a live Coupang response (the keys
 * {@code fetchStatus} already reads). The rest are inferred from the register payload schema, so a missing
 * key must parse to {@code null} rather than throw — the service decides what is fatal (a missing sale price
 * is, a missing tag list is not).</p>
 *
 * @param productName  marketplace product name (Coupang {@code sellerProductName})
 * @param categoryCode marketplace leaf category code (Coupang {@code displayCategoryCode}) — D16, display only
 * @param status       marketplace status mapped to our lifecycle status
 * @param tags         marketplace search tags (item level on Coupang); never null, empty when absent
 * @param options      marketplace options; never null, empty when absent
 */
public record ImportedProduct(String productName, String categoryCode, ListingStatus status,
                              List<String> tags, List<Option> options) {

    /**
     * One marketplace option (Coupang {@code items[]} entry).
     *
     * @param itemName            option name as shown on the marketplace
     * @param vendorItemId        Coupang vendorItemId — null while the option is not approved yet (D20)
     * @param sellerProductItemId Coupang option-update id
     * @param salePrice           current selling price
     * @param originalPrice       strike-through price
     * @param stockQuantity       Coupang {@code maximumBuyCount}; null → the stock policy falls back (부록 A)
     */
    public record Option(String itemName, String vendorItemId, String sellerProductItemId,
                         BigDecimal salePrice, BigDecimal originalPrice, Integer stockQuantity) {
    }
}
