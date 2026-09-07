package com.pms.service;

import com.pms.domain.Platform;
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
     * @param masterLinked 마스터 연결 여부 필터(2609_22/04). {@code null} = 필터 없음(기존 동작),
     *                     {@code false} = 마스터 미연결 셀만, {@code true} = 연결된 셀만. 3값이라
     *                     {@code boolean} 이 아니라 {@code Boolean} 이다.
     * @return Page of ProductListingResponse objects
     */
    Page<ProductListingResponse> getByPlatform(Platform platform, int page, int size, Boolean masterLinked);

    /**
     * Update an existing product listing.
     *
     * Validates:
     * - platformProductId uniqueness (if changed)
     * - Referenced category, delivery, package exist (if provided)
     *
     * Uses immutable pattern: creates new instance with updated fields.
     *
     * <p>⚠️ 2609_22/D32: 마스터에 연결된 셀은 이 경로로 수정할 수 없다(400). 이 update 는 옵션을 전부
     * delete + recreate 하므로 마스터 FK·platformOptionId·approvalStatus·priceSource 가 통째로 사라진다.</p>
     *
     * @param id Product listing ID to update
     * @param request CreateProductListingRequest with updated field values
     * @return Updated ProductListingResponse
     * @throws IllegalArgumentException if new platformProductId already exists, or the listing is
     *                                  linked to a master product
     * @throws ResourceNotFoundException if listing not found, or referenced entities not found
     */
    ProductListingResponse update(Long id, CreateProductListingRequest request);

    /**
     * Delete a product listing by ID.
     *
     * Removes the listing from the database.
     * Note: Cascade behavior for related options/products handled by database FK constraints.
     *
     * <p>⚠️ 2609_22/D32: 마스터에 연결된 셀은 이 경로로 삭제할 수 없다(400).</p>
     *
     * @param id Product listing ID to delete
     * @throws IllegalArgumentException if the listing is linked to a master product
     * @throws ResourceNotFoundException if listing not found
     */
    void delete(Long id);
}
