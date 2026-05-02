package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Optional;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Update product request")
public class UpdateProductRequest {

    @Schema(description = "Barcode ID", example = "1234567890123")
    private Optional<String> barcodeId;

    @Schema(description = "Brand name", example = "Samsung")
    private Optional<String> brand;

    @Schema(description = "Price", example = "999.99")
    private Optional<BigDecimal> price;

    @Schema(description = "Product name", example = "Galaxy S21")
    private Optional<String> productName;

    @Schema(description = "Store name", example = "Best Buy")
    private Optional<String> store;

    @Schema(description = "Unit of measurement", example = "KG")
    private Optional<String> unit;

    @Schema(description = "Volume height", example = "160mm")
    private Optional<String> volumeHeight;

    @Schema(description = "Volume long", example = "75mm")
    private Optional<String> volumeLong;

    @Schema(description = "Volume short", example = "8.9mm")
    private Optional<String> volumeShort;

    @Schema(description = "Weight", example = "170g")
    private Optional<String> weight;

    @Schema(description = "Product description")
    private Optional<String> description;

    @Schema(description = "Product name short", example = "Samsung Galaxy S21")
    private Optional<String> name;

    @Schema(description = "Active status", example = "true")
    private Optional<Boolean> active;
}
