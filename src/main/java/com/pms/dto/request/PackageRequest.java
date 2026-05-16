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
@Schema(description = "Package creation/update request")
public class PackageRequest {

    @NotBlank(message = "Type is required")
    @Schema(description = "Package type", example = "S")
    private String type;

    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0", inclusive = true, message = "Cost must be >= 0")
    @Schema(description = "Package cost in currency", example = "2.50")
    private BigDecimal cost;

    @NotNull(message = "effectiveDate is required")
    @Schema(description = "Date when package rate becomes effective", example = "2026-05-13")
    private LocalDate effectiveDate;

    @NotNull(message = "isDefault is required")
    @Schema(description = "Whether this is the default package", example = "false")
    private Boolean isDefault;
}
