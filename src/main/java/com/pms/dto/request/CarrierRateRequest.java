package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Carrier rate request")
public class CarrierRateRequest {

    @NotBlank(message = "Carrier is required")
    @Schema(description = "Carrier name", example = "DHL")
    private String carrier;

    @NotBlank(message = "Type is required")
    @Schema(description = "Delivery type", example = "EXPRESS")
    private String type;

    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0", inclusive = true, message = "Cost must be >= 0")
    @Schema(description = "Delivery cost", example = "15.50")
    private BigDecimal cost;

    @NotNull(message = "effectiveDate is required")
    @Schema(description = "Effective date", example = "2026-05-13")
    private LocalDate effectiveDate;

    @NotNull(message = "isDefault is required")
    @Schema(description = "Whether this is the default carrier rate", example = "false")
    private Boolean isDefault;
}
