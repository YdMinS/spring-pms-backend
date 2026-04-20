package com.pms.dto.response;

import com.pms.domain.Product;
import com.pms.domain.Unit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
    private Long id;
    private Long barcodeId;
    private String brand;
    private String productName;
    private Long price;
    private String description;
    private String store;
    private Unit unit;
    private String volumeHeight;
    private String volumeLong;
    private String volumeShort;
    private String weight;
    private String imageUrl;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductResponse of(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .productName(product.getProductName())
                .price(product.getPrice())
                .description(product.getDescription())
                .store(product.getStore())
                .unit(product.getUnit())
                .volumeHeight(product.getVolumeHeight())
                .volumeLong(product.getVolumeLong())
                .volumeShort(product.getVolumeShort())
                .weight(product.getWeight())
                .imageUrl(product.getImageUrl())
                .active(product.getActive())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
