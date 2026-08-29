package com.pms.repository;

import com.pms.domain.ProductListingProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Repository for ProductListingProduct entity.
 *
 * Provides CRUD operations and custom queries for product compositions within listing options.
 */
@Repository
public interface ProductListingProductRepository extends JpaRepository<ProductListingProduct, Long> {

    /**
     * Find all product compositions for a specific listing option.
     *
     * @param productListingOptionId ID of the parent ProductListingOption
     * @return List of ProductListingProduct entities
     */
    List<ProductListingProduct> findByProductListingOptionId(Long productListingOptionId);

    /**
     * Batch-fetch BOM lines for several listing options at once (N+1 guard).
     *
     * <p>Used by the read-only channel-sync preview (89), which compares every cell option's quantities
     * against the master in a fixed number of queries regardless of cell/option count.</p>
     *
     * @param productListingOptionIds IDs of the parent ProductListingOptions
     * @return List of ProductListingProduct entities across all given options
     */
    List<ProductListingProduct> findByProductListingOptionIdIn(Collection<Long> productListingOptionIds);

    /**
     * Delete all product compositions for a specific listing option.
     * Useful when updating an option's composition.
     *
     * @param productListingOptionId ID of the parent ProductListingOption
     */
    void deleteByProductListingOptionId(Long productListingOptionId);

    /**
     * Delete all product compositions for a specific product listing.
     * Used when updating a listing's options.
     *
     * @param productListingId ID of the parent ProductListing
     */
    @Modifying
    @Query("DELETE FROM ProductListingProduct p WHERE p.productListingOption.productListing.id = :productListingId")
    void deleteByProductListingId(@Param("productListingId") Long productListingId);
}
