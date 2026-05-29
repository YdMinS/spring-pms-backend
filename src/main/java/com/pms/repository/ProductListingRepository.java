package com.pms.repository;

import com.pms.domain.ProductListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for ProductListing entity.
 *
 * Provides CRUD operations and custom queries for platform product listings.
 */
@Repository
public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {

    /**
     * Find all product listings for a specific platform with pagination.
     *
     * @param platform Platform identifier (e.g., "COUPANG")
     * @param pageable Pagination information
     * @return Page of ProductListing entities
     */
    Page<ProductListing> findByPlatform(String platform, Pageable pageable);

    /**
     * Find a product listing by platform product ID.
     *
     * @param platformProductId Platform's product ID
     * @return Optional containing the ProductListing if found
     */
    Optional<ProductListing> findByPlatformProductId(String platformProductId);

    /**
     * Check if a product listing exists for the given platform product ID.
     *
     * @param platformProductId Platform's product ID
     * @return true if exists, false otherwise
     */
    boolean existsByPlatformProductId(String platformProductId);
}
