package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * A tenant-wide catalog entry for a detail-page image zone (FEATURE_2609_03).
 *
 * <p>Before this catalog existed, a zone was nothing but a free-text string typed into
 * {@code DetailTemplate.blocks[].bind}, so every new template invented its own zones and the master image
 * screen drew the same slot once per template. This entity promotes those strings to a shared, tenant-wide
 * list; {@code DetailTemplateService} now rejects a {@code bind} that is not in it.</p>
 *
 * <p><b>{@code code} is immutable — there is deliberately no API that changes it.</b> Its value IS the
 * mapping key stored in {@link MasterImageZoneAssignment#getZoneId()} (and in
 * {@code DetailTemplate.blocks[].bind}); those are plain strings with no FK, so editing {@code code} would
 * silently detach every master photo already mapped to the zone. Renaming for humans happens on
 * {@link #getName()} only.</p>
 *
 * <p>⚠️ {@link MasterImageZoneAssignment#SOURCE_ZONE} ({@code __source__}) is NOT a catalog entry — it is
 * the reserved cover-photo key, not a zone. Never seed or create it here.</p>
 *
 * <p>⚠️ {@code sortOrder} is assigned as {@code max + 1} on create (creation order) and is never touched by
 * a rename; there is no reordering endpoint.</p>
 *
 * <p>Tenant-isolated via Hibernate {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — never set
 * {@code tenantId} manually in the builder). Immutable (no {@code @Setter}) — a rename rebuilds via
 * {@code toBuilder}, per backend entity rules.</p>
 */
@Entity
@Table(name = "detail_image_group")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class DetailImageGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Auto-set/filtered by Hibernate {@code @TenantId} — never set manually in the builder. */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * Immutable identifier, equal to the {@code zone_id} / {@code blocks[].bind} string. Generated from the
     * name on create (see {@code DetailImageGroupServiceImpl.generateCode}); never updated afterwards.
     */
    @Column(name = "code", nullable = false, length = 100)
    private String code;

    /** Display name (the only mutable field). Unique per tenant. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Position in the catalog list (0-based, creation order). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
