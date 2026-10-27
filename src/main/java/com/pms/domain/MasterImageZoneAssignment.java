package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Field-to-image mapping (M:N) between a {@link MasterProductImage} pool asset and a detail zone, the
 * cover photo or a thumbnail template image group (FEATURE_2608_06 / 37 · FEATURE_2610_33 D9).
 *
 * <p>One pool image can be mapped to several zones and to the cover photo — this table is the reuse point.
 * A detail zone (e.g. {@code product_photos}) may hold many images (ordered by {@code sortOrder}); the
 * cover photo uses the reserved {@link #SOURCE_ZONE} key and is kept to a single mapping by service logic.
 * A thumbnail template image group uses the reserved key {@link ThumbnailTemplate#imageGroupKey} and holds at
 * most one image ({@code MasterProductImageServiceImpl#setZoneImages}).</p>
 *
 * <p>⚠️ The {@code UNIQUE(master_product_image_id, zone_id)} only blocks mapping the <b>same</b> image to
 * the same zone twice. It cannot enforce "a single {@code __source__} image" (two different images each
 * mapped to {@code __source__} would pass the DB constraint). That single-cover invariant is guaranteed
 * only by {@code setSourceImage} (delete-then-insert); never insert a {@code __source__} mapping elsewhere.</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the master/image (repositories expose only
 * master-/image-scoped finders). Immutable (no {@code @Setter}); re-order rebuilds via delete-then-insert.</p>
 */
@Entity
// 🔴 The unique constraint mirrors changeset 024 so the generated test schema matches production. Leaving it
// out let delete-then-insert ordering bugs pass every test and only surface as a 500 on MySQL.
@Table(name = "master_image_zone_assignment", uniqueConstraints = @UniqueConstraint(
        name = "uq_miza_image_zone", columnNames = {"master_product_image_id", "zone_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MasterImageZoneAssignment extends BaseEntity {

    /** Reserved {@code zoneId} for the single cover photo (never hardcode this string elsewhere). */
    public static final String SOURCE_ZONE = "__source__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_image_id", nullable = false)
    private MasterProductImage image;

    /**
     * Detail zone id, {@link #SOURCE_ZONE} for the cover photo, or {@link ThumbnailTemplate#imageGroupKey} for a
     * thumbnail template image group.
     */
    @Column(name = "zone_id", nullable = false, length = 100)
    private String zoneId;

    /** Position within the zone (0-based). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
