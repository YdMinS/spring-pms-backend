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

    @Schema(description = "Unit of measurement", example = "KG")
    @Builder.Default
    private Optional<String> unit = Optional.empty();

    @Schema(description = "Volume height", example = "160mm")
    @Builder.Default
    private Optional<String> volumeHeight = Optional.empty();

    @Schema(description = "Volume long", example = "75mm")
    @Builder.Default
    private Optional<String> volumeLong = Optional.empty();

    @Schema(description = "Volume short", example = "8.9mm")
    @Builder.Default
    private Optional<String> volumeShort = Optional.empty();

    @Schema(description = "Weight", example = "170g")
    @Builder.Default
    private Optional<String> weight = Optional.empty();

    @Schema(description = "Product description")
    @Builder.Default
    private Optional<String> description = Optional.empty();

    @Schema(description = "Product name short", example = "Samsung Galaxy S21")
    @Builder.Default
    private Optional<String> name = Optional.empty();

    @Schema(description = "Active status", example = "true")
    @Builder.Default
    private Optional<Boolean> active = Optional.empty();
}
