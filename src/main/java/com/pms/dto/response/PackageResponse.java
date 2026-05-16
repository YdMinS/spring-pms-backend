package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for package information. Contains all fields of a Package entity.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageResponse {

    @Schema(description = "Unique identifier (auto-generated)", example = "1")
    private Long id;

    @Schema(description = "Package type/category", example = "STANDARD", maxLength = 50)
    private String type;

    @Schema(description = "Shipping cost", example = "15.50", type = "number")
    private BigDecimal cost;

    @Schema(description = "Date from which package is valid (ISO)", example = "2026-05-16", format = "date")
    private LocalDate effectiveDate;

    @Schema(description = "Is default package type? (Only one can be true)", example = "false")
    private Boolean isDefault;
}
