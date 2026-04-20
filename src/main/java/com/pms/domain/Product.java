package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@AttributeOverrides({
    @AttributeOverride(name = "createdAt", column = @Column(name = "created_date")),
    @AttributeOverride(name = "updatedAt", column = @Column(name = "modified_date"))
})
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "barcode_id", nullable = false)
    private Long barcodeId;

    @Column(nullable = false, length = 255)
    private String brand;

    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    @Column(nullable = false)
    private Long price;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 255)
    private String store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Unit unit;

    @Column(name = "volume_height", length = 255)
    private String volumeHeight;

    @Column(name = "volume_long", length = 255)
    private String volumeLong;

    @Column(name = "volume_short", length = 255)
    private String volumeShort;

    @Column(nullable = false, length = 255)
    private String weight;

    @Column(nullable = true)
    private Boolean active;

    @Column(name = "image_url", length = 500)
    private String imageUrl;
}
