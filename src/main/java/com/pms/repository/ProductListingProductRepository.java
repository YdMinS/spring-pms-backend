package com.pms.repository;

import com.pms.domain.ProductListingProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
     * Delete all product compositions for a specific listing option.
     * Useful when updating an option's composition.
     *
     * @param productListingOptionId ID of the parent ProductListingOption
     */
    void deleteByProductListingOptionId(Long productListingOptionId);
}
