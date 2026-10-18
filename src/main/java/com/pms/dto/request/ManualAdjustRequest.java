package com.pms.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Replace a manual line's manual_qty with an absolute value of 1 or more (FEATURE_2610_09 D23 · D77). */
public record ManualAdjustRequest(
        @NotNull @Min(1) Integer manualQty
) {}
