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
@Schema(description = "Margin preset response")
public class MarginPolicyResponse {

    @Schema(description = "Margin preset ID", example = "1")
    private Long id;

    @Schema(description = "Seller ID", example = "3")
    private Long sellerId;

    @Schema(description = "Seller display name", example = "행복상회")
    private String sellerName;

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Net-profit ratio (0.1500 = 15%)", example = "0.1500")
    private BigDecimal marginRate;

    @Schema(description = "Display discount rate (0.2000 = 20% strike-through)", example = "0.2000")
    private BigDecimal displayDiscountRate;
}
