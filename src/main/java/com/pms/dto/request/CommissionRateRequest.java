package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for creating/updating commission rates.
 *
 * Validation rules:
 * - platform: Required, non-blank
 * - categoryId: Optional (null for platform default rate)
 * - rate: Required, must be between 0 and 1.0 (inclusive)
 *
 * Example payloads:
 * 1. Category-specific: {"platform": "COUPANG", "categoryId": 5, "rate": 0.05}
 * 2. Platform default: {"platform": "COUPANG", "categoryId": null, "rate": 0.03}
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Commission rate creation/update request")
public class CommissionRateRequest {

    @NotBlank(message = "Platform is required")
    @Schema(
        description = "Platform name (e.g., COUPANG, GMARKET, NAVER)",
        example = "COUPANG",
        required = true
    )
    private String platform;

    @Nullable
    @Schema(
        description = "Category ID for category-specific rate. " +
                      "Leave null for platform default rate.",
        example = "5",
        nullable = true
    )
    private Long categoryId;

    @NotNull(message = "Rate is required")
    @DecimalMin(value = "0", inclusive = true, message = "Rate must be >= 0")
    @DecimalMax(value = "1", inclusive = true, message = "Rate must be <= 1")
    @Schema(
        description = "Commission rate as decimal (0.00 - 1.00). " +
                      "Example: 0.05 = 5% commission",
        example = "0.05",
        required = true,
        minimum = "0",
        maximum = "1"
    )
    private BigDecimal rate;

    @Schema(
        description = "Flag indicating if this is the default rate for the platform",
        example = "true",
        required = false
    )
    private Boolean isDefault;
}
