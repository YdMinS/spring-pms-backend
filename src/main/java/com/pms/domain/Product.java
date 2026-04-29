package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "barcode_id", nullable = false)
    private Long barcodeId;

    @Column(name = "brand", nullable = false, length = 255)
    private String brand;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    @Column(name = "store", nullable = false, length = 255)
    private String store;

    @Column(name = "unit", nullable = false, length = 255)
    private String unit;

    @Column(name = "volume_height", length = 255)
    private String volumeHeight;

    @Column(name = "volume_long", length = 255)
    private String volumeLong;

    @Column(name = "volume_short", length = 255)
    private String volumeShort;

    @Column(name = "weight", nullable = false, length = 255)
    private String weight;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "active")
    private Boolean active;
}
