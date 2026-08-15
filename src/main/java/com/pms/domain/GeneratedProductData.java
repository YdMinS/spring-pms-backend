package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Auto-generated assets for one channel cell ({@link ProductListing}) — FEATURE_2608_06 / 3b-2.
 *
 * <p>1:1 with a listing (UNIQUE {@code product_listing_id}): the rendered thumbnail URL (S3),
 * the detail-page HTML (seam stub in 3b-2, real generator in Step 2), the template used, and the
 * generation timestamp. Produced/refreshed by {@link com.pms.service.ListingAssetService#regenerateAssets}
 * — first run creates it, propagation (3d) re-runs the same seam and upserts the same row.</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the parent {@link ProductListing} (which is
 * tenant-scoped). Repositories expose only a listing-scoped finder (no tenant-less {@code findAll}).
 * The FK is {@code product_listing_id} (Design 2), NOT Design 1's {@code channel_listing_id}.</p>
 */
@Entity
@Table(name = "generated_product_data",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_gpd_listing", columnNames = {"product_listing_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class GeneratedProductData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The channel cell these assets belong to (isolation source). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    private ProductListing productListing;

    /** Rendered thumbnail (S3 public URL on dev/prod, disk-relative path on local/test). */
    @Column(name = "thumbnail_url", length = 1024)
    private String thumbnailUrl;

    /** Detail-page HTML. Seam stub in 3b-2 (representative image + one line), real HTML in Step 2. */
    @Column(name = "detail_html", columnDefinition = "TEXT")
    private String detailHtml;

    /** Thumbnail template used for the render (tenant default in 3b-2). */
    @Column(name = "template_id")
    private Long templateId;

    /** Last (re)generation timestamp. */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
}
