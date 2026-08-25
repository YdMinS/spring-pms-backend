package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Standard-category → platform code mapping (FEATURE_2608_06 / 44).
 *
 * <p>A {@link Category} (standard, oclyx-independent) maps to at most one platform code per platform. The
 * master picks a single standard category; the per-platform marketplace code is resolved from this mapping
 * (adding a marketplace = filling in mappings, no model change).</p>
 *
 * <p>No {@code @TenantId} and no {@link BaseEntity} — a config child reached only through the parent
 * {@link Category}. ⚠️ Tenant asymmetry: this table has no tenant column, so it must be reached
 * <b>only via a category id</b> (the repository exposes {@code category_id}-based finders only — no
 * tenant-less {@code findAll}/plain {@code findByPlatform}). Immutable ({@code toBuilder}, no
 * {@code @Setter}).</p>
 */
@Entity
@Table(name = "category_mapping",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_catmap_category_platform", columnNames = {"category_id", "platform"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CategoryMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The standard category this code maps from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    @Column(name = "platform_category_id", nullable = false, length = 100)
    private String platformCategoryId;

    /** Display path cached at lookup time (nullable). */
    @Column(name = "platform_category_name", length = 255)
    private String platformCategoryName;

    /**
     * FK promotion (FEATURE_2608_06 / 52): the marketplace category node that owns the mall code + commission.
     * Nullable during the transition (the string {@code platformCategoryId} columns above are kept, expand-
     * contract; physical removal is a follow-up). New logic resolves the code/commission through this FK.
     *
     * <p>⚠️ Tenant asymmetry: {@code category_mapping} has no tenant column (it is reached via a tenant-scoped
     * {@link Category}), but {@link PlatformCategory} IS tenant-scoped — a mapping must only link a
     * PlatformCategory of the same tenant (guaranteed by the 53 import / mapping service). LAZY.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "platform_category_id_fk", nullable = true)
    private PlatformCategory platformCategory;
}
