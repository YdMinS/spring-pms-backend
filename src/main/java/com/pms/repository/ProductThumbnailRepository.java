package com.pms.repository;

import com.pms.domain.ProductThumbnail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@code @TenantId} auto-filters every query to the current tenant — do NOT add manual tenant
 * conditions. {@code (productId, sellerId)} is the natural upsert key within a tenant.
 */
public interface ProductThumbnailRepository extends JpaRepository<ProductThumbnail, Long> {

    /** Existing thumbnail for upsert (generate/override). */
    Optional<ProductThumbnail> findByProductIdAndSellerId(Long productId, Long sellerId);

    /** All per-seller thumbnails of a product (listByProduct). */
    List<ProductThumbnail> findByProductId(Long productId);
}
