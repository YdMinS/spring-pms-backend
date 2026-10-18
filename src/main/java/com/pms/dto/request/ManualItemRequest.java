package com.pms.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Manual line add/accumulate input. The seller is required (FEATURE_2610_09 D11). */
public record ManualItemRequest(
        @NotNull Long productId,
        @NotNull Long sellerId,
        @NotNull @Min(1) Integer quantity
) {}
