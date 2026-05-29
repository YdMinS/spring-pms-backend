package com.pms.service;

import com.pms.dto.request.CreateProductListingRequest;
import com.pms.dto.response.ProductListingResponse;
import com.pms.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;

/**
 * ProductListingService - Service interface for ProductListing management
 *
 * Handles business logic for platform product listings (e.g., Coupang).
 *
 * Core responsibilities:
 * - CRUD operations for ProductListing entities
 * - Reference validation (Category, CarrierRate, Package FK checks)
 * - Uniqueness constraint on platformProductId per listing
 * - Pagination support for platform-based queries
 *
 * This service does NOT handle ProductListingOption or ProductListingProduct.
 * Those are managed separately via their own services.
 *
 * @see ProductListingServiceImpl for implementation details
 */
public interface ProductListingService {

    /**
     * Create a new product listing on a platform.
     *
     * Validates:
     * - platform and platformProductId are not blank
     * - platformProductId is unique (not already in use)
     * - Referenced category, delivery, package exist (if provided)
     *
     * @param request CreateProductListingRequest containing platform, platformProductId, and optional references
     * @return ProductListingResponse with created listing ID and details
     * @throws IllegalArgumentException if platformProductId already exists
     * @throws ResourceNotFoundException if referenced category, delivery, or package not found
     */
    ProductListingResponse create(CreateProductListingRequest request);

    /**
     * Retrieve a product listing by ID.
     *
     * Fetches the listing with all related information (category name, carrier name, package type).
     *
     * @param id Product listing ID
     * @return ProductListingResponse with all listing details
     * @throws ResourceNotFoundException if listing not found
     */
    ProductListingResponse getById(Long id);

    /**
     * Retrieve all product listings for a specific platform with pagination.
     *
     * Results are sorted by ID in descending order (most recent first).
     * Page size defaults to 20 if invalid size provided.
     *
     * @param platform Platform identifier (e.g., "COUPANG", "AMAZON")
     * @param page Page number (0-indexed)
     * @param size Page size (items per page)
     * @return Page of ProductListingResponse objects
     */
    Page<ProductListingResponse> getByPlatform(String platform, int page, int size);

    /**
     * Update an existing product listing.
     *
     * Validates:
     * - platformProductId uniqueness (if changed)
     * - Referenced category, delivery, package exist (if provided)
     *
     * Uses immutable pattern: creates new instance with updated fields.
     *
     * @param id Product listing ID to update
     * @param request CreateProductListingRequest with updated field values
     * @return Updated ProductListingResponse
     * @throws IllegalArgumentException if new platformProductId already exists
     * @throws ResourceNotFoundException if listing not found, or referenced entities not found
     */
    ProductListingResponse update(Long id, CreateProductListingRequest request);

    /**
     * Delete a product listing by ID.
     *
     * Removes the listing from the database.
     * Note: Cascade behavior for related options/products handled by database FK constraints.
     *
     * @param id Product listing ID to delete
     * @throws ResourceNotFoundException if listing not found
     */
    void delete(Long id);
}
