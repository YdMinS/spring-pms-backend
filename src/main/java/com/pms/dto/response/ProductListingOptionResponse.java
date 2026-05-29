package com.pms.dto.response;

import com.pms.domain.ProductListingOption;
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
@Schema(description = "Product listing option response")
public class ProductListingOptionResponse {

    @Schema(description = "Product listing option ID", example = "1")
    private Long id;

    @Schema(description = "Product listing ID (parent)", example = "1")
    private Long productListingId;

    @Schema(description = "Option name", example = "Size M")
    private String optionName;

    @Schema(description = "Selling price", example = "12999.99")
    private BigDecimal sellingPrice;

    @Schema(description = "Platform option ID", example = "opt_12345")
    private String platformOptionId;

    public static ProductListingOptionResponse of(ProductListingOption option) {
        return ProductListingOptionResponse.builder()
                .id(option.getId())
                .productListingId(option.getProductListing() != null ? option.getProductListing().getId() : null)
                .optionName(option.getOptionName())
                .sellingPrice(option.getSellingPrice())
                .platformOptionId(option.getPlatformOptionId())
                .build();
    }
}
