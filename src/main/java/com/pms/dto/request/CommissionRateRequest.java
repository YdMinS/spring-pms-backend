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

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Commission rate creation/update request")
public class CommissionRateRequest {

    @NotBlank(message = "Platform is required")
    @Schema(description = "Platform name", example = "COUPANG")
    private String platform;

    @Nullable
    @Schema(description = "Category ID (optional for platform default)", example = "5")
    private Long categoryId;

    @NotNull(message = "Rate is required")
    @DecimalMin(value = "0", inclusive = true, message = "Rate must be >= 0")
    @DecimalMax(value = "1", inclusive = true, message = "Rate must be <= 1")
    @Schema(description = "Commission rate between 0 and 1.0", example = "0.05")
    private BigDecimal rate;
}
