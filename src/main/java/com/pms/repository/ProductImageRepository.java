package com.pms.repository;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Number of gallery rows pointing at the same storage object — must be 0 before the file can be deleted
     *  (clipboard reference copy shares {@code image_url} across products).
     *  ⚠️ {@link ProductImage} has no {@code @TenantId}, so this count spans every tenant — that is
     *  intentional (over-protecting a file is the safe side). Do NOT "fix" the missing tenant filter. */
    long countByImageUrl(String imageUrl);

    /**
     * Row count for one product, shown as informational history in the product usage screen
     * (FEATURE_2609_69 / A). History never blocks deletion — it is displayed only.
     */
    long countByProductId(Long productId);

    /**
     * Move every row of this product onto another product (FEATURE_2609_69 / B, merge).
     *
     * <p>🔴 {@code clearAutomatically}/{@code flushAutomatically} are load-bearing, not decoration: a bulk
     * UPDATE bypasses the persistence context, so without them the delete guard later in the same merge
     * transaction would read a stale first-level cache, wrongly report "still linked" and roll the whole
     * merge back.</p>
     *
     * @return number of rows moved
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductImage i SET i.product = :target WHERE i.product.id = :source")
    int reassignProduct(@Param("target") Product target, @Param("source") Long source);
}
