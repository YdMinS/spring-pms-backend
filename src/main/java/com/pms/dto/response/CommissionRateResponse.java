package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Commission rate response")
public class CommissionRateResponse {

    @Schema(description = "Unique identifier for the commission rate", example = "1")
    private Long id;

    @Schema(description = "Platform name", example = "COUPANG")
    private String platform;

    @Schema(description = "Category ID (null for platform default)", example = "5")
    private Long categoryId;

    @Schema(description = "Commission rate between 0 and 1.0", example = "0.05")
    private BigDecimal rate;
}
