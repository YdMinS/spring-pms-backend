package com.pms.domain;

import com.pms.domain.converter.DetailBlockListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.List;

/**
 * A tenant-wide detail-page template library entry: an ordered array of {@link DetailBlock}s rendered
 * top-to-bottom (flow layout) by {@link com.pms.service.DetailHtmlRenderer} (FEATURE_2608_06 / Step 2-1).
 *
 * <p>Mirror of {@link ThumbnailTemplate} but for the detail page: {@code blocks} array instead of
 * {@code elements}, HTML string output instead of a raster. Tenant-isolated via Hibernate
 * {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — no manual tenant conditions). Templates
 * are NOT seller-owned — they are a shared library. Exactly one template per tenant is the default
 * ({@code isDefault=true}); currently the seeder guarantees that single default (there is no create yet —
 * visual block editing is a later step).</p>
 */
@Entity
@Table(name = "detail_template")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class DetailTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    /** Ordered block array, JSON-serialized into a TEXT column (H2/MySQL portable). */
    @Convert(converter = DetailBlockListConverter.class)
    @Column(name = "blocks", columnDefinition = "TEXT")
    private List<DetailBlock> blocks;

    @Column(name = "active", nullable = false)
    private Boolean active;

    /**
     * Exactly one template per tenant is the default. NOT nullable → the seeder must always set it
     * explicitly (an unset builder default would INSERT null and violate the NOT NULL constraint).
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;
}
