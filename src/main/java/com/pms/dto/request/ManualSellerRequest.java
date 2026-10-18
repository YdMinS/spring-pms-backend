package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

/** Move a manual line to another seller (FEATURE_2610_09 D13 · D24). */
public record ManualSellerRequest(
        @NotNull Long sellerId
) {}
