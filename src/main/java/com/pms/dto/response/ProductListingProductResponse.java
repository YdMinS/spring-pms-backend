package com.pms.dto.response;

import com.pms.domain.ProductListingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product listing product (composition) response")
public class ProductListingProductResponse {

    @Schema(description = "Product listing product ID", example = "1")
    private Long id;

    @Schema(description = "Product listing option ID (parent)", example = "1")
    private Long productListingOptionId;

    @Schema(description = "Product ID", example = "1")
    private Long productId;

    @Schema(description = "Product name", example = "Galaxy S21")
    private String productName;

    @Schema(description = "Quantity of product in this option", example = "3")
    private Integer quantity;

    public static ProductListingProductResponse of(ProductListingProduct plp) {
        return ProductListingProductResponse.builder()
                .id(plp.getId())
                .productListingOptionId(plp.getProductListingOption() != null ? plp.getProductListingOption().getId() : null)
                .productId(plp.getProduct() != null ? plp.getProduct().getId() : null)
                .productName(plp.getProduct() != null ? plp.getProduct().getProductName() : null)
                .quantity(plp.getQuantity())
                .build();
    }
}
