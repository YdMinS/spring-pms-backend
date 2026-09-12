package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

    // Box dimensions in cm (PLAN 2609_38 D5). 0 / 1000 / 22.55 are all 400:
    // @DecimalMin blocks the "unset" 0 that the backfill left behind, @DecimalMax caps the range
    // (DECIMAL(5,1) itself would accept up to 9999.9) and @Digits blocks the second decimal that the
    // DB would otherwise round silently (22.55 -> 22.6).
    @NotNull(message = "Width is required")
    @DecimalMin(value = "0.1", message = "Width must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Width must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Width allows one decimal place")
    @Schema(description = "Box width in cm", example = "22.0", type = "number")
    private BigDecimal widthCm;

    @NotNull(message = "Length is required")
    @DecimalMin(value = "0.1", message = "Length must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Length must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Length allows one decimal place")
    @Schema(description = "Box length in cm", example = "19.0", type = "number")
    private BigDecimal lengthCm;

    @NotNull(message = "Height is required")
    @DecimalMin(value = "0.1", message = "Height must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Height must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Height allows one decimal place")
    @Schema(description = "Box height in cm", example = "9.0", type = "number")
    private BigDecimal heightCm;
}
