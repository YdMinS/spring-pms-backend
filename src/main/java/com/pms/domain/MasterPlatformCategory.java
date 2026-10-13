package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A master's own platform category for one platform — 「개별 지정」 (FEATURE_2610_12 / D40).
 *
 * <p>At most one row per (master, platform). No row = the master follows its standard category's
 * {@link CategoryMapping} for that platform. Read order (D41) = the channel cell's own category →
 * this row → the standard category mapping, resolved in {@code MasterChannelConfigServiceImpl}.
 * {@code ChannelAddServiceImpl} and {@code CoupangListingImportServiceImpl} count a row as "the platform
 * has a category" next to the mapping.</p>
 *
 * <p>Changing the master's standard category deletes every row of that master (D42,
 * {@code MasterProductServiceImpl.setCategory}). Deleting the master deletes them too
 * ({@code MasterDeleteServiceImpl}).</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the tenant-scoped master (the repository exposes
 * master-scoped finders only). The linked {@link PlatformCategory} is tenant-scoped; the service only
 * links a row found through the tenant-filtered {@code findByPlatformAndCode}. Immutable (no
 * {@code @Setter}); updates go through {@code toBuilder}.</p>
 */
@Entity
// 🔴 The unique constraint mirrors changeset 104 so the generated test schema matches production.
@Table(name = "master_platform_category", uniqueConstraints = @UniqueConstraint(
        name = "uq_mplatcat_master_platform", columnNames = {"master_product_id", "platform"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterPlatformCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    private MasterProduct masterProduct;

    @Column(name = "platform", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Platform platform;

    /** The marketplace category node (owns the mall code + commission). LAZY. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "platform_category_id", nullable = false)
    private PlatformCategory platformCategory;

    /** Display path cached at pick time (nullable) — same role as {@code CategoryMapping.platformCategoryName}. */
    @Column(name = "platform_category_name", length = 255)
    private String platformCategoryName;
}
