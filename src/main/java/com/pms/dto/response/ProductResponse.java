package com.pms.dto.response;

import com.pms.domain.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product response")
public class ProductResponse {

    @Schema(description = "Product ID", example = "1")
    private Long id;

    @Schema(description = "Barcode ID", example = "1234567890123")
    private String barcodeId;

    @Schema(description = "Brand name", example = "Samsung")
    private String brand;

    @Schema(description = "Price", example = "999.99")
    private BigDecimal price;

    @Schema(description = "Product name", example = "Galaxy S21")
    private String productName;

    @Schema(description = "Store name", example = "Best Buy")
    private String store;

    @Schema(description = "Unit of measurement", example = "KG")
    private String unit;

    @Schema(description = "Volume height", example = "160mm")
    private String volumeHeight;

    @Schema(description = "Volume long", example = "75mm")
    private String volumeLong;

    @Schema(description = "Volume short", example = "8.9mm")
    private String volumeShort;

    @Schema(description = "Weight", example = "170g")
    private String weight;

    @Schema(description = "Product description")
    private String description;

    @Schema(description = "Product name short", example = "Samsung Galaxy S21")
    private String name;

    @Schema(description = "Product image URL (filename)", example = "product_1_1234567890_abc123.jpg")
    private String imageUrl;

    @Schema(description = "Active status", example = "true")
    private Boolean active;

    @Schema(description = "Created timestamp", example = "2024-01-01T00:00:00")
    private LocalDateTime createdDate;

    @Schema(description = "Last updated timestamp", example = "2024-01-01T00:00:00")
    private LocalDateTime modifiedDate;

    public static ProductResponse of(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .price(product.getPrice())
                .productName(product.getProductName())
                .store(product.getStore())
                .unit(product.getUnit())
                .volumeHeight(product.getVolumeHeight())
                .volumeLong(product.getVolumeLong())
                .volumeShort(product.getVolumeShort())
                .weight(product.getWeight())
                .description(product.getDescription())
                .name(product.getName())
                .imageUrl(product.getImageUrl())
                .active(product.getActive())
                .createdDate(product.getCreatedAt())
                .modifiedDate(product.getUpdatedAt())
                .build();
    }
}
