package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create product listing option request")
public class CreateProductListingOptionRequest {

    @NotNull(message = "Product listing ID cannot be null")
    @Schema(description = "Product listing ID (parent)", example = "1")
    private Long productListingId;

    @NotBlank(message = "Option name cannot be blank")
    @Size(max = 255, message = "Option name must not exceed 255 characters")
    @Schema(description = "Option name", example = "Size M")
    private String optionName;

    @NotNull(message = "Selling price cannot be null")
    @DecimalMin(value = "0.01", message = "Selling price must be greater than 0")
    @Schema(description = "Selling price", example = "12999.99")
    private BigDecimal sellingPrice;

    @Size(max = 255, message = "Platform option ID must not exceed 255 characters")
    @Schema(description = "Platform option ID (optional)", example = "opt_12345")
    private String platformOptionId;
}
