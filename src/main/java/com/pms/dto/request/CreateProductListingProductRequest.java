package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create product listing product (composition) request")
public class CreateProductListingProductRequest {

    @NotNull(message = "Product listing option ID cannot be null")
    @Schema(description = "Product listing option ID (parent)", example = "1")
    private Long productListingOptionId;

    @NotNull(message = "Product ID cannot be null")
    @Schema(description = "Product ID", example = "1")
    private Long productId;

    @NotNull(message = "Quantity cannot be null")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Schema(description = "Quantity of product in this option", example = "3")
    private Integer quantity;
}
