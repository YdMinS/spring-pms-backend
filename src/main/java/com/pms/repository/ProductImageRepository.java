package com.pms.repository;

import com.pms.domain.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link ProductImage} — the product's image gallery (FEATURE_2608_06 / 39).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent {@link com.pms.domain.Product}
 * (callers resolve the tenant-scoped product first via {@code ProductRepository.findScopedById}). Only
 * product-scoped finders are exposed; a tenant-less {@code findAll} must NOT be used to read images.</p>
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** All gallery images of a product in gallery order (list view + next-sortOrder computation). */
    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);
}
