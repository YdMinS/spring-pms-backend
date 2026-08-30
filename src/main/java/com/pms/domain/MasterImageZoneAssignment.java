package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Field-to-image mapping (M:N) between a {@link MasterProductImage} pool asset and a detail zone or the
 * cover photo (FEATURE_2608_06 / 37).
 *
 * <p>One pool image can be mapped to several zones and to the cover photo — this table is the reuse point.
 * A detail zone (e.g. {@code product_photos}) may hold many images (ordered by {@code sortOrder}); the
 * cover photo uses the reserved {@link #SOURCE_ZONE} key and is kept to a single mapping by service logic.</p>
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
@Table(name = "master_image_zone_assignment")
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

    /** Detail zone id, or {@link #SOURCE_ZONE} for the cover photo. */
    @Column(name = "zone_id", nullable = false, length = 100)
    private String zoneId;

    /** Position within the zone (0-based). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
