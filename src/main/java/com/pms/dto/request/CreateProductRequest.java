package com.pms.dto.request;

import com.pms.domain.Unit;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateProductRequest {
    @NotNull(message = "Barcode ID is required")
    private Long barcodeId;

    @NotBlank(message = "Brand is required")
    @Size(max = 255, message = "Brand must not exceed 255 characters")
    private String brand;

    @NotBlank(message = "Product name is required")
    @Size(max = 500, message = "Product name must not exceed 500 characters")
    private String productName;

    @NotNull(message = "Price is required")
    @PositiveOrZero(message = "Price must be zero or positive")
    private Long price;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotBlank(message = "Store is required")
    @Size(max = 255, message = "Store must not exceed 255 characters")
    private String store;

    @NotNull(message = "Unit is required")
    private Unit unit;

    @Size(max = 255, message = "Volume height must not exceed 255 characters")
    private String volumeHeight;

    @Size(max = 255, message = "Volume long must not exceed 255 characters")
    private String volumeLong;

    @Size(max = 255, message = "Volume short must not exceed 255 characters")
    private String volumeShort;

    @NotBlank(message = "Weight is required")
    @Size(max = 255, message = "Weight must not exceed 255 characters")
    private String weight;
}
