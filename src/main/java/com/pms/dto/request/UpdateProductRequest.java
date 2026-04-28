package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Update product request")
public class UpdateProductRequest {

    @Schema(description = "Brand name", example = "Samsung")
    @Size(max = 255, message = "Brand must not exceed 255 characters")
    private String brand;

    @Schema(description = "Price", example = "999.99")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    private BigDecimal price;

    @Schema(description = "Product name", example = "Galaxy S21")
    @Size(max = 500, message = "Product name must not exceed 500 characters")
    private String productName;

    @Schema(description = "Store name", example = "Best Buy")
    @Size(max = 255, message = "Store must not exceed 255 characters")
    private String store;

    @Schema(description = "Unit of measurement", example = "KG")
    @Size(max = 255, message = "Unit must not exceed 255 characters")
    private String unit;

    @Schema(description = "Volume height", example = "160mm")
    @Size(max = 255, message = "Volume height must not exceed 255 characters")
    private String volumeHeight;

    @Schema(description = "Volume long", example = "75mm")
    @Size(max = 255, message = "Volume long must not exceed 255 characters")
    private String volumeLong;

    @Schema(description = "Volume short", example = "8.9mm")
    @Size(max = 255, message = "Volume short must not exceed 255 characters")
    private String volumeShort;

    @Schema(description = "Weight", example = "170g")
    @Size(max = 255, message = "Weight must not exceed 255 characters")
    private String weight;

    @Schema(description = "Product description")
    private String description;

    @Schema(description = "Product name short", example = "Samsung Galaxy S21")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Schema(description = "Active status", example = "true")
    private Boolean active;
}
