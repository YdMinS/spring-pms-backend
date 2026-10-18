package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edit a card's alias and masked number. The owner seller never changes (FEATURE_2610_18 D91). */
public record SellerCardUpdateRequest(
        @NotBlank @Size(max = 50) String alias,
        @NotBlank @Size(max = 30) String maskedNumber
) {}
