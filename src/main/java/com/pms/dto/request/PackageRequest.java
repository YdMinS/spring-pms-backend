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

/**
 * Request DTO for package creation/update. All fields required.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageRequest {

    @NotBlank(message = "Package type is required")
    @Schema(description = "Package type/category", example = "STANDARD", maxLength = 50)
    private String type;

    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0.00")
    @Schema(description = "Shipping cost", example = "15.50", type = "number")
    private BigDecimal cost;

    @NotNull(message = "Effective date is required")
    @Schema(description = "Date from which package is valid (ISO)", example = "2026-05-16", format = "date")
    private LocalDate effectiveDate;

    @NotNull(message = "isDefault flag is required")
    @Schema(description = "Is default package type? (Only one can be true)", example = "false")
    private Boolean isDefault;
}
