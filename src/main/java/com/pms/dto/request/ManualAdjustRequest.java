package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

/** 라인 manual_qty 절대값 교체. */
public record ManualAdjustRequest(
        @NotNull Integer manualQty
) {}
