package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create product request")
public class CreateProductRequest {

    @Size(max = 50, message = "Barcode ID must not exceed 50 characters")
    @Schema(description = "Barcode ID (optional)", example = "1234567890123")
    private String barcodeId;

    @Size(max = 255, message = "Brand must not exceed 255 characters")
    @Schema(description = "Brand name", example = "Samsung")
    private String brand;

    @Schema(description = "Price", example = "999.99")
    private BigDecimal price;

    @NotBlank(message = "Product name cannot be blank")
    @Size(max = 500, message = "Product name must not exceed 500 characters")
    @Schema(description = "Product name", example = "Galaxy S21")
    private String productName;

    /**
     * Purchase places (FEATURE_2609_76 / D1 · D3) — ids from {@code GET /api/admin/purchase-places}. Null or
     * empty = none. Duplicates are ignored; an unknown id is a 400.
     */
    @Schema(description = "Purchase place ids", example = "[1, 3]")
    private List<Long> purchasePlaceIds;

    @Size(max = 255, message = "Net content unit must not exceed 255 characters")
    @Schema(description = "Unit of net content (KG, G, L, ML)", example = "KG")
    private String netContentUnit;

    @Size(max = 255, message = "Package height must not exceed 255 characters")
    @Schema(description = "Package height", example = "160mm")
    private String packageHeight;

    @Size(max = 255, message = "Package length must not exceed 255 characters")
    @Schema(description = "Package length", example = "75mm")
    private String packageLength;

    @Size(max = 255, message = "Package width must not exceed 255 characters")
    @Schema(description = "Package width", example = "8.9mm")
    private String packageWidth;

    @Size(max = 255, message = "Net content must not exceed 255 characters")
    @Schema(description = "Amount of product inside the package (mass or volume)", example = "170g")
    private String netContent;

    /**
     * Piece count (D6 · D12). {@code BigDecimal}, not {@code Integer}, on purpose: Jackson would silently
     * truncate {@code 1.5} to {@code 1} for an Integer field, and D12 rejects decimals — the service does.
     */
    @Schema(description = "Piece count (whole number >= 1)", example = "30")
    private BigDecimal countQuantity;

    @Schema(description = "Piece count unit (개, 장, 매, 봉, 팩, 롤, 입)", example = "개")
    private String countUnit;

    @Schema(description = "Product description")
    private String description;

}
