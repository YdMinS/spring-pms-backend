package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Register a card under a seller (FEATURE_2610_18 D52). {@code maskedNumber} = the masked number as printed on
 * receipts — 13 or more digits in a row (hyphens and spaces removed) → 400 (D92 · D99 ②).
 */
public record SellerCardCreateRequest(
        @NotNull Long sellerId,
        @NotBlank @Size(max = 50) String alias,
        @NotBlank @Size(max = 30) String maskedNumber
) {}
