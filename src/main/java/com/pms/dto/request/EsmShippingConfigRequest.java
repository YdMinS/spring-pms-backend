package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * ESM channel shipping settings upsert (FEATURE_2610_26 / D36 · D66): the six {@code esm.*} input keys → value,
 * replaced whole. Policy keys and unknown keys are dropped; the server writes the policy keys itself.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "ESM shipping settings of one sales channel (esm.* input keys; others dropped)")
public class EsmShippingConfigRequest {

    @NotNull(message = "settings is required")
    private Map<String, String> settings;
}
