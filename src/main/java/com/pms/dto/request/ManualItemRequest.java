package com.pms.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 수동 라인 추가/누적 입력. */
public record ManualItemRequest(
        @NotNull Long productId,
        @NotNull @Min(1) Integer quantity
) {}
