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
    @Builder.Default
    private Optional<String> barcodeId = Optional.empty();

    @Schema(description = "Brand name", example = "Samsung")
    @Builder.Default
    private Optional<String> brand = Optional.empty();

    @Schema(description = "Price", example = "999.99")
    @Builder.Default
    private Optional<BigDecimal> price = Optional.empty();

    @Schema(description = "Product name", example = "Galaxy S21")
    @Builder.Default
    private Optional<String> productName = Optional.empty();

    @Schema(description = "Store name", example = "Best Buy")
    @Builder.Default
    private Optional<String> store = Optional.empty();

    @Schema(description = "Unit of net content (KG, G, L, ML)", example = "KG")
    @Builder.Default
    private Optional<String> netContentUnit = Optional.empty();

    @Schema(description = "Package height", example = "160mm")
    @Builder.Default
    private Optional<String> packageHeight = Optional.empty();

    @Schema(description = "Package length", example = "75mm")
    @Builder.Default
    private Optional<String> packageLength = Optional.empty();

    @Schema(description = "Package width", example = "8.9mm")
    @Builder.Default
    private Optional<String> packageWidth = Optional.empty();

    @Schema(description = "Amount of product inside the package (mass or volume)", example = "170g")
    @Builder.Default
    private Optional<String> netContent = Optional.empty();

    @Schema(description = "Product description")
    @Builder.Default
    private Optional<String> description = Optional.empty();

    @Schema(description = "Active status", example = "true")
    @Builder.Default
    private Optional<Boolean> active = Optional.empty();
}
