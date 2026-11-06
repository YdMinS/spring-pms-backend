package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * LotteON channel shipping settings upsert (FEATURE_2610_24 / D42 · D65): the three {@code lotteon.*} input keys →
 * value, replaced whole. Policy keys and unknown keys are dropped; the server writes the policy keys itself.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "LotteON shipping settings of one sales channel (lotteon.* input keys; others dropped)")
public class LotteonShippingConfigRequest {

    @NotNull(message = "settings is required")
    private Map<String, String> settings;
}
