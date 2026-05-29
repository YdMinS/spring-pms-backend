package com.pms.repository;

import com.pms.domain.ProductListingOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ProductListingOption entity.
 *
 * Provides CRUD operations and custom queries for product listing options.
 */
@Repository
public interface ProductListingOptionRepository extends JpaRepository<ProductListingOption, Long> {

    /**
     * Find all options for a specific product listing.
     *
     * @param productListingId ID of the parent ProductListing
     * @return List of ProductListingOption entities
     */
    List<ProductListingOption> findByProductListingId(Long productListingId);

    /**
     * Find an option by platform option ID.
     *
     * @param platformOptionId Platform-specific option ID
     * @return Optional containing the ProductListingOption if found
     */
    Optional<ProductListingOption> findByPlatformOptionId(String platformOptionId);

    /**
     * Check if an option exists with the given platform option ID.
     *
     * @param platformOptionId Platform-specific option ID
     * @return true if exists, false otherwise
     */
    boolean existsByPlatformOptionId(String platformOptionId);
}
