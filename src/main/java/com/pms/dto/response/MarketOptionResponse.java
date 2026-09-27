package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One option as it exists on the market right now, marked with the channel option that already holds its
 * id (2609_74/D13). Read-only projection.
 */
@Getter
@Builder
@Schema(description = "Market option as it exists now + the channel option already holding its id")
public class MarketOptionResponse {

    @Schema(description = "Market option name", example = "59g 6개")
    private String itemName;

    @Schema(description = "Market option id (Coupang vendorItemId); null before approval", example = "987654321")
    private String vendorItemId;

    @Schema(description = "Market option-update id (Coupang sellerProductItemId)", example = "123456")
    private String sellerProductItemId;

    @Schema(description = "Current sale price on the market; null when absent", example = "12900")
    private BigDecimal salePrice;

    @Schema(description = "Channel option id already holding this market option id; null when none", example = "50")
    private Long linkedOptionId;

    @Schema(description = "Name of that channel option; null when none", example = "6개")
    private String linkedOptionName;
}
