package com.pms.service;

import com.pms.dto.response.ProductThumbnailResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Per-product-per-seller thumbnail lifecycle (FEATURE_2608_05 / 02): generate/regenerate (upsert),
 * manual override, delete, and per-product listing. Rendering goes through {@link ThumbnailRenderer};
 * source image bytes through {@link ProductImageLoader}; template selection via seller → tenant-default.
 */
public interface ProductThumbnailService {

    /** Generate or regenerate (idempotent upsert on productId+sellerId) the templated thumbnail. */
    ProductThumbnailResponse generate(Long productId, Long sellerId);

    /** Replace with a manually uploaded image (source=MANUAL_OVERRIDE, renderer bypassed). */
    ProductThumbnailResponse override(Long productId, Long sellerId, MultipartFile file);

    /** Delete the (productId, sellerId) thumbnail (best-effort storage cleanup). */
    void delete(Long productId, Long sellerId);

    /** All per-seller thumbnails of a product (tenant auto-isolated). */
    List<ProductThumbnailResponse> listByProduct(Long productId);
}
