package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
// 🔴 The barcode is unique PER TENANT, never globally (changeset 098): the same retail EAN legitimately
// lives in several tenants' catalogues, so a bare UNIQUE(barcode_id) would let the first tenant to
// register a code take it away from all the others.
// ⚠️ barcode_id stays nullable — MySQL (and H2 in MySQL mode) allow any number of NULLs under a unique
// key, which is what keeps the products that carry no barcode legal. ProductServiceImpl normalises "" to
// NULL so a blank never becomes a colliding empty string.
@Table(name = "products",
        uniqueConstraints = @UniqueConstraint(name = "uq_products_tenant_barcode",
                columnNames = {"tenant_id", "barcode_id"}))
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

    // 🔴 The legacy `store` column (free text) is intentionally NOT mapped any more (FEATURE_2609_76 / D11):
    // purchase places live in product_purchase_place (changeset 102). The column stays in the table, untouched,
    // so a bad migration can be undone from it — dropping it is separate work. Do not re-add a field for it.

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

    // Piece count inside the package (FEATURE_2609_76 / D6 · D12): a whole number >= 1 plus one of the fixed
    // count units (개·장·매·봉·팩·롤·입). Both set or both null — ProductServiceImpl.validateCount.
    // 🔴 Deliberately NOT folded into netContent/netContentUnit: the Coupang auto-fill reads that pair as
    // "mass, otherwise volume", so a count unit there would be stamped as volume (D7).
    @Column(name = "count_quantity")
    private Integer countQuantity;

    @Column(name = "count_unit", length = 10)
    private String countUnit;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;


    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "active")
    private Boolean active;
}
