package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * An input image owned by a {@link MasterProduct}, grouped into a named zone (FEATURE_2608_06 / Step 2-1).
 *
 * <p>Zones ({@code zoneId}, e.g. {@code product_photos} / {@code detail_photos}) are the source images
 * for {@link DetailBlock} {@code imageZone} blocks: within a zone, images render in {@code sortOrder}
 * order. The master owns them so a re-generation / propagation reuses the same source set.</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the parent {@link MasterProduct} (repositories
 * expose only master-scoped finders, no tenant-less {@code findAll}); same convention as
 * {@link MasterProductComponent} / {@link GeneratedProductData}. Immutable (no {@code @Setter}) —
 * reorder rebuilds via {@code toBuilder}.</p>
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

    /** Logical group, e.g. {@code product_photos}, {@code detail_photos}. */
    @Column(name = "zone_id", nullable = false, length = 100)
    private String zoneId;

    /** Position within the zone (0-based, gaps allowed until reorder). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** {@code ImageStorageService.uploadBytes} return value (S3 public URL on dev/prod). */
    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;
}
