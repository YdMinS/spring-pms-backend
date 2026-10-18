package com.pms.dto.response;

import java.util.List;

/**
 * One component (Product) row of the purchase list.
 *
 * <p>Seller chosen (FEATURE_2610_09 D2): the three numbers are that seller's share, {@code shares} holds that
 * one share and {@code lines} only that seller's lines (D6). 「전체」 (D22 · D58): neededQty = sum of the shares,
 * remainingQty = sum of each share's shortfall (one seller's surplus never covers another's), purchasedQty =
 * sum of each share's purchase capped at its own need — so neededQty − purchasedQty = remainingQty.</p>
 *
 * <p>The numbers are display only; which list a row sits in comes from the shares' states (D57 · D78).</p>
 *
 * <p>brand = the product's brand (null when none) — shown so a same-named product of another brand is not picked up.</p>
 */
public record PurchaseProductGroup(
        Long productId,
        String productName,
        String brand,
        int neededQty,
        int purchasedQty,
        int remainingQty,
        List<PurchaseShare> shares,
        List<PurchaseLine> lines
) {}
