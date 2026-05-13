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
@Schema(description = "Carrier rate response")
public class CarrierRateResponse {

    @Schema(description = "Unique identifier for the carrier rate", example = "1")
    private Long id;

    @Schema(description = "Carrier company name", example = "DHL")
    private String carrier;

    @Schema(description = "Delivery type", example = "EXPRESS")
    private String type;

    @Schema(description = "Delivery cost in currency", example = "15.50")
    private BigDecimal cost;

    @Schema(description = "Date when rate becomes effective", example = "2026-05-13")
    private LocalDate effectiveDate;

    @Schema(description = "Whether this is the default carrier rate globally", example = "false")
    private Boolean isDefault;
}
