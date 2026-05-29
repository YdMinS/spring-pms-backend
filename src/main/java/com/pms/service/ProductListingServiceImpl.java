package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CarrierRate;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.dto.request.CreateProductListingRequest;
import com.pms.dto.response.ProductListingResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProductListingServiceImpl - Product listing service implementation
 *
 * Handles CRUD operations for ProductListing entities with validation
 * for referenced entities (Category, CarrierRate, Package).
 *
 * Transaction Management:
 * - Class-level @Transactional(readOnly = true) for all read operations
 * - Method-level @Transactional override for write operations (create, update, delete)
 *
 * Key Features:
 * - platformProductId uniqueness validation
 * - FK reference validation before save
 * - Immutable pattern (toBuilder) for updates
 * - Pagination with default page size
 *
 * @see ProductListingRepository for persistence
 * @see ProductListingService for interface contract
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductListingServiceImpl implements ProductListingService {

    private final ProductListingRepository productListingRepository;
    private final CategoryRepository categoryRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PackageRepository packageRepository;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Create a new product listing with validation.
     *
     * Validates:
     * 1. platformProductId is not already in use
     * 2. All optional references (category, delivery, package) exist if provided
     *
     * Throws IllegalArgumentException if platformProductId exists.
     * Throws ResourceNotFoundException if any reference entity not found.
     *
     * @param request CreateProductListingRequest
     * @return ProductListingResponse with created listing
     */
    @Override
    @Transactional
    public ProductListingResponse create(CreateProductListingRequest request) {
        // Validate platform and platformProductId uniqueness
        if (productListingRepository.existsByPlatformProductId(request.getPlatformProductId())) {
            throw new IllegalArgumentException(
                    "Product listing with platformProductId '" + request.getPlatformProductId() + "' already exists"
            );
        }

        // Resolve optional references
        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        }

        CarrierRate delivery = null;
        if (request.getDeliveryId() != null) {
            delivery = carrierRateRepository.findById(request.getDeliveryId())
                    .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", request.getDeliveryId()));
        }

        Package pkg = null;
        if (request.getPackageId() != null) {
            pkg = packageRepository.findById(request.getPackageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Package", request.getPackageId()));
        }

        // Build and save
        ProductListing listing = ProductListing.builder()
                .platform(request.getPlatform())
                .platformProductId(request.getPlatformProductId())
                .category(category)
                .delivery(delivery)
                .package_(pkg)
                .build();

        ProductListing saved = productListingRepository.save(listing);
        return ProductListingResponse.of(saved);
    }

    /**
     * Retrieve a product listing by ID.
     *
     * @param id Product listing ID
     * @return ProductListingResponse with all listing details
     * @throws ResourceNotFoundException if listing not found
     */
    @Override
    public ProductListingResponse getById(Long id) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
        return ProductListingResponse.of(listing);
    }

    /**
     * Retrieve all product listings for a specific platform with pagination.
     *
     * Results sorted by ID DESC (most recent first).
     * Auto-corrects invalid page size to default (20).
     *
     * @param platform Platform identifier
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return Page of ProductListingResponse
     */
    @Override
    public Page<ProductListingResponse> getByPlatform(String platform, int page, int size) {
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<ProductListing> listingPage = productListingRepository.findByPlatform(platform, pageable);
        return listingPage.map(ProductListingResponse::of);
    }

    /**
     * Update an existing product listing.
     *
     * Validates:
     * 1. platformProductId uniqueness (if changed)
     * 2. All optional references exist (if provided)
     *
     * Uses immutable pattern: old instance -> toBuilder() -> new instance.
     *
     * @param id Product listing ID
     * @param request CreateProductListingRequest with updated values
     * @return Updated ProductListingResponse
     * @throws ResourceNotFoundException if listing not found
     * @throws IllegalArgumentException if new platformProductId already exists
     */
    @Override
    @Transactional
    public ProductListingResponse update(Long id, CreateProductListingRequest request) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));

        // Check uniqueness of new platformProductId if changed
        if (!listing.getPlatformProductId().equals(request.getPlatformProductId())) {
            if (productListingRepository.existsByPlatformProductId(request.getPlatformProductId())) {
                throw new IllegalArgumentException(
                        "Product listing with platformProductId '" + request.getPlatformProductId() + "' already exists"
                );
            }
        }

        // Resolve optional references
        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        }

        CarrierRate delivery = null;
        if (request.getDeliveryId() != null) {
            delivery = carrierRateRepository.findById(request.getDeliveryId())
                    .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", request.getDeliveryId()));
        }

        Package pkg = null;
        if (request.getPackageId() != null) {
            pkg = packageRepository.findById(request.getPackageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Package", request.getPackageId()));
        }

        // Update using immutable pattern
        ProductListing updated = listing.toBuilder()
                .platform(request.getPlatform())
                .platformProductId(request.getPlatformProductId())
                .category(category)
                .delivery(delivery)
                .package_(pkg)
                .build();

        ProductListing saved = productListingRepository.save(updated);
        return ProductListingResponse.of(saved);
    }

    /**
     * Delete a product listing.
     *
     * Removes the listing and optionally cascades to related entities
     * depending on database FK constraints.
     *
     * @param id Product listing ID
     * @throws ResourceNotFoundException if listing not found
     */
    @Override
    @Transactional
    public void delete(Long id) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
        productListingRepository.delete(listing);
    }
}
