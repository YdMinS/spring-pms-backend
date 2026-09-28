package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
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

    /**
     * Purchase places (FEATURE_2609_76). Absent/null = keep; {@code []} = clear; a list = replace with exactly
     * these ids.
     */
    @Schema(description = "Purchase place ids (replaces the whole set)", example = "[1, 3]")
    @Builder.Default
    private Optional<List<Long>> purchasePlaceIds = Optional.empty();

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

    /**
     * Piece count (D6 · D12). 🔴 The pair is replaced as a unit whenever {@link #countUnit} is sent: then an
     * absent/null {@code countQuantity} means "no count". Clear both = {@code countUnit: ""} with
     * {@code countQuantity: null}. When {@code countUnit} is absent, {@code countQuantity} alone updates the
     * number and keeps the stored unit.
     */
    @Schema(description = "Piece count (whole number >= 1)", example = "30")
    @Builder.Default
    private Optional<BigDecimal> countQuantity = Optional.empty();

    @Schema(description = "Piece count unit (개, 장, 매, 봉, 팩, 롤, 입); \"\" clears the count", example = "개")
    @Builder.Default
    private Optional<String> countUnit = Optional.empty();

    @Schema(description = "Product description")
    @Builder.Default
    private Optional<String> description = Optional.empty();

    @Schema(description = "Active status", example = "true")
    @Builder.Default
    private Optional<Boolean> active = Optional.empty();
}
