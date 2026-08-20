package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A source image owned by a {@link Product} — the product's 1:N gallery (FEATURE_2608_06 / 39).
 *
 * <p><b>Single source of the original images.</b> This gallery replaces the single
 * {@link Product#getImageUrl()} column as the place a product's original photos live; the master
 * image pool (40) will <b>live-link</b> these rows by id (a stable slot), so there is no image
 * duplication between a product and the masters that reference it.</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the parent {@link Product} (callers resolve the
 * tenant-scoped product first via {@code ProductRepository.findScopedById}; there is no tenant-less
 * {@code findAll} over images). Same convention as {@link MasterProductImage}.</p>
 *
 * <p>⚠️ {@code @Builder(toBuilder = true)} is required — {@code ProductImageService.replaceImage} does an
 * update-in-place via {@code toBuilder()} to keep the same {@code id} (so a master 40-reference never
 * dangles). Immutable (no {@code @Setter}).</p>
 */
@Entity
@Table(name = "product_image")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProductImage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Position within the product's gallery (0-based, gaps allowed after a delete). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** {@code ImageStorageService.uploadImage} return value (S3 public URL on dev/prod, disk path on local). */
    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;
}
