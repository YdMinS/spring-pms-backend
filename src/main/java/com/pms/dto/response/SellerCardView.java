package com.pms.dto.response;

/** One visible card (FEATURE_2610_18 D52 · D91). */
public record SellerCardView(
        Long id,
        Long sellerId,
        String sellerName,
        String alias,
        String maskedNumber
) {}
