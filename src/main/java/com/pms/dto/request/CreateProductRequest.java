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
@Schema(description = "Create product request")
public class CreateProductRequest {

    @NotBlank(message = "Barcode ID cannot be blank")
    @Size(max = 50, message = "Barcode ID must not exceed 50 characters")
    @Schema(description = "Barcode ID", example = "1234567890123")
    private String barcodeId;

    @Size(max = 255, message = "Brand must not exceed 255 characters")
    @Schema(description = "Brand name", example = "Samsung")
    private String brand;

    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @Schema(description = "Price", example = "999.99")
    private BigDecimal price;

    @NotBlank(message = "Product name cannot be blank")
    @Size(max = 500, message = "Product name must not exceed 500 characters")
    @Schema(description = "Product name", example = "Galaxy S21")
    private String productName;

    @Size(max = 255, message = "Store must not exceed 255 characters")
    @Schema(description = "Store name", example = "Best Buy")
    private String store;

    @Size(max = 255, message = "Unit must not exceed 255 characters")
    @Schema(description = "Unit of measurement", example = "KG")
    private String unit;

    @Size(max = 255, message = "Volume height must not exceed 255 characters")
    @Schema(description = "Volume height", example = "160mm")
    private String volumeHeight;

    @Size(max = 255, message = "Volume long must not exceed 255 characters")
    @Schema(description = "Volume long", example = "75mm")
    private String volumeLong;

    @Size(max = 255, message = "Volume short must not exceed 255 characters")
    @Schema(description = "Volume short", example = "8.9mm")
    private String volumeShort;

    @Size(max = 255, message = "Weight must not exceed 255 characters")
    @Schema(description = "Weight", example = "170g")
    private String weight;

    @Schema(description = "Product description")
    private String description;

    @Size(max = 100, message = "Name must not exceed 100 characters")
    @Schema(description = "Product name short", example = "Samsung Galaxy S21")
    private String name;
}
