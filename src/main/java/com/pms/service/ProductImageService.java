package com.pms.service;

import com.pms.dto.response.ProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Product image gallery (1:N) service (FEATURE_2608_06 / 39).
 *
 * <p>Every method resolves the tenant-scoped product first (404 if absent/cross-tenant) and, after any
 * mutation, re-syncs the representative {@code Product.imageUrl} to the gallery's first image so the live
 * consumers ({@code ProductImageLoader}, thumbnail base) keep working. Ownership + isolation flow through
 * the parent {@link com.pms.domain.Product} ({@code ProductImage} has no {@code @TenantId}).</p>
 */
public interface ProductImageService {

    /** Append the uploaded files to the product's gallery; returns the full gallery in order. */
    List<ProductImageResponse> addImages(Long productId, List<MultipartFile> files);

    /**
     * Copy other products' gallery images into this product by <b>reference</b> (FEATURE_2609_62): a new row
     * per source id sharing the source {@code imageUrl} — no file is re-uploaded. Sources that no longer
     * exist or belong to another tenant are skipped silently (a 404 would block the remaining good ones);
     * an all-skipped/empty request is a 400. Returns the full gallery in order.
     */
    List<ProductImageResponse> copyImages(Long productId, List<Long> sourceImageIds);

    /** The product's gallery in order. */
    List<ProductImageResponse> list(Long productId);

    /** Replace one image in place (same {@code ProductImage.id}) with a new upload. */
    ProductImageResponse replaceImage(Long productId, Long imageId, MultipartFile file);

    /** Reorder the gallery to exactly {@code imageIds} (set must match the current gallery). */
    List<ProductImageResponse> reorder(Long productId, List<Long> imageIds);

    /** Remove one image from the gallery. */
    void deleteImage(Long productId, Long imageId);
}
