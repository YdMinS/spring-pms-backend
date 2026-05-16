package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Package response")
public class PackageResponse {

    @Schema(description = "Unique identifier for the package", example = "1")
    private Long id;

    @Schema(description = "Package type", example = "S")
    private String type;

    @Schema(description = "Package cost in currency", example = "2.50")
    private BigDecimal cost;

    @Schema(description = "Date when package rate becomes effective", example = "2026-05-13")
    private LocalDate effectiveDate;

    @Schema(description = "Whether this is the default package globally", example = "false")
    private Boolean isDefault;
}
