package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

/** Purchase settings save request (FEATURE_2610_09 / D65 · D72). */
public record PurchaseSettingRequest(
        @NotNull Boolean stockLinked) {
}
