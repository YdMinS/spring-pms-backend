package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A pool image owned by a {@link MasterProduct} (FEATURE_2608_06 / Step 2-1 → 37).
 *
 * <p>⚠️ As of 37 this is a pure <b>pool asset</b> — it is no longer bound to a single zone at upload time.
 * Zone / cover-photo membership lives in {@link MasterImageZoneAssignment} (M:N), so one pool image can be
 * reused across several zones and the cover photo. {@code sortOrder} is now the position within the pool.</p>
 *
 * <p>⚠️ {@code zoneId} is kept nullable during the transition (backfilled into assignments, then written no
 * more — see changeset 024). Do NOT set it on new pool uploads; physical column removal is a follow-up.</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the parent {@link MasterProduct} (repositories
 * expose only master-scoped finders, no tenant-less {@code findAll}); same convention as
 * {@link MasterProductComponent} / {@link GeneratedProductData}. Immutable (no {@code @Setter}).</p>
 */
@Entity
@Table(name = "master_product_image")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterProductImage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_id", nullable = false)
    private MasterProduct masterProduct;

    /**
     * Legacy zone binding — transition-only, nullable (37). New pool uploads leave this null; mapping now
     * lives in {@link MasterImageZoneAssignment}. Do NOT write this on new pool images.
     */
    @Column(name = "zone_id", nullable = true, length = 100)
    private String zoneId;

    /** Position within the master's image pool (0-based, gaps allowed after a delete). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** {@code ImageStorageService.uploadBytes} return value (S3 public URL on dev/prod). */
    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;
}
