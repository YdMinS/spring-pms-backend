package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.Map;

/**
 * Tenant-shared master product (Design 2, FEATURE_2608_06 / 3a).
 *
 * <p>The master sits <b>additively</b> on top of {@link ProductListing}: a listing is the per-channel
 * cell, and the master is the grouping node that many channel cells point back to (via
 * {@code product_listing.master_product_id}). 3a introduces the master as a <b>backfill-created,
 * read-only</b> grouping node — one master per existing listing (shared-id 1:1 backfill).</p>
 *
 * <p>3b-1 (FEATURE_2608_06 / 3b-1) adds the content columns (source_image_url / field_values /
 * active) plus the component + option sets ({@link MasterProductComponent},
 * {@link MasterProductOption}). Thumbnail/detail/price auto-generation stays deferred to 3b-2.</p>
 *
 * <p>Tenant isolation: {@code @TenantId} auto-filters query-based SELECTs and auto-stamps INSERTs.
 * Do NOT add manual tenant conditions to queries. PK {@code find()} is NOT tenant-filtered, so
 * tenant-scoped reads must use the query-based {@code findScopedById} (see MasterProductRepository).</p>
 */
@Entity
@Table(name = "master_product")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProduct extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 009). Hibernate auto-sets this on INSERT and auto-filters
    // query-based SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    /** Base image override for thumbnail generation; null = derived from BOM (filled by 3b-2 upload). */
    @Column(name = "source_image_url", length = 1024)
    private String sourceImageUrl;

    /** UI input field values (JSON TEXT, H2/MySQL portable). Blank-fallback handled in 3b-2. */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "field_values", columnDefinition = "TEXT")
    private Map<String, String> fieldValues;

    /**
     * Soft-delete / activation flag. NOT nullable → create/service must always set it explicitly.
     * ⚠️ MySQL BIT trap: changeset 010 re-types this to BIT(1) on MySQL (Hibernate maps Boolean to BIT),
     * mirroring changeset 006.
     */
    @Column(name = "active", nullable = false)
    private Boolean active;

    /**
     * Default delivery (carrier rate) for the price engine (FEATURE_2608_06 / 13). Nullable; an option may
     * override it ({@link MasterProductOption#getDelivery()}). Resolution = option override ?? this default.
     * Not a boolean → no MySQL BIT trap.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_delivery_id", nullable = true)
    private CarrierRate defaultDelivery;

    /**
     * Default box (package) cost for the price engine (FEATURE_2608_06 / 13). Nullable; an option may
     * override it ({@link MasterProductOption#getPackage_()}). Resolution = option override ?? this default.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_package_id", nullable = true)
    private Package defaultPackage;
}
