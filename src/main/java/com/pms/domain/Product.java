package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "modified_date")
    private LocalDateTime updatedAt;

    @Column(name = "barcode_id", nullable = true, length = 50)
    private String barcodeId;

    @Column(name = "brand", nullable = true, length = 255)
    private String brand;

    @Column(name = "price", nullable = true)
    private BigDecimal price;

    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    @Column(name = "store", nullable = true, length = 255)
    private String store;

    // Unit of netContent. Mass (KG/G) or volume (L/ML) -- see netContent below.
    @Column(name = "net_content_unit", nullable = true, length = 255)
    private String netContentUnit;

    @Column(name = "package_height", nullable = true, length = 255)
    private String packageHeight;

    @Column(name = "package_length", nullable = true, length = 255)
    private String packageLength;

    @Column(name = "package_width", nullable = true, length = 255)
    private String packageWidth;

    // Amount of product inside the package (GS1 netContent). Covers BOTH mass and volume, which is why
    // this is not "weight"/"netWeight" -- those are mass-only and cannot hold an ML value (changeset 046).
    @Column(name = "net_content", nullable = true, length = 255)
    private String netContent;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;


    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "active")
    private Boolean active;
}
