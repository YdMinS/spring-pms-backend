package com.pms.controller;

import com.pms.dto.request.CreateProductListingRequest;
import com.pms.dto.response.ProductListingResponse;
import com.pms.dto.common.ResponseDTO;
import com.pms.service.ProductListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ProductListingController - REST API endpoints for ProductListing management
 *
 * Manages platform product listings (e.g., Coupang marketplace listings).
 * ProductListing represents a product registered on a platform with:
 * - Platform ID (e.g., "COUPANG")
 * - Platform Product ID (업체상품 ID)
 * - Listing Name (for list view disambiguation)
 * - Default Category, Carrier Rate, Package for margin calculation
 *
 * Base path: /api/product-listings
 *
 * Endpoints:
 * - POST / : Create new listing (ADMIN)
 * - GET /{id} : Get listing by ID
 * - GET ?platform=... : Get listings by platform (paginated)
 * - PATCH /{id} : Update listing (ADMIN)
 * - DELETE /{id} : Delete listing (ADMIN)
 *
 * @see ProductListingService for business logic
 * @see ProductListingResponse for response structure
 */
@RestController
@RequestMapping("/api/product-listings")
@RequiredArgsConstructor
@Tag(name = "Product Listing", description = "Product listing management API (platform registrations)")
public class ProductListingController {

    private final ProductListingService productListingService;

    /**
     * Create a new product listing on a platform.
     *
     * Creates a ProductListing with platform, platformProductId, and name.
     * Optional fields (category, delivery, package) are used for margin calculation.
     * platformProductId must be unique.
     *
     * @param request CreateProductListingRequest (platform, platformProductId, name required)
     * @return HTTP 201 Created with ProductListingResponse
     * @throws IllegalArgumentException if platformProductId already exists
     * @throws ResourceNotFoundException if category/delivery/package not found
     */
    @PostMapping
    @Operation(
            summary = "Create product listing",
            description = "Create a new product listing on a platform. platformProductId must be unique."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
            responseCode = "201",
            description = "Product listing created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Validation error, duplicate platformProductId, or invalid references",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    @ApiResponse(
            responseCode = "401",
            description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    public ResponseEntity<ResponseDTO<ProductListingResponse>> create(
            @Valid @RequestBody CreateProductListingRequest request) {
        ProductListingResponse response = productListingService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    /**
     * Retrieve a product listing by ID.
     *
     * @param id Product listing ID
     * @return HTTP 200 OK with ProductListingResponse containing listing details
     * @throws ResourceNotFoundException if listing not found
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get product listing by ID",
            description = "Retrieve a specific product listing with all details (category, delivery, package)"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Product listing found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Product listing not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    public ResponseEntity<ResponseDTO<ProductListingResponse>> getById(
            @PathVariable Long id) {
        ProductListingResponse response = productListingService.getById(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Retrieve all product listings for a specific platform with pagination.
     *
     * Supports pagination via page and size parameters.
     * Results are sorted by ID in descending order (most recent first).
     *
     * @param platform Platform identifier (e.g., "COUPANG", "AMAZON") - required
     * @param page Page number (0-indexed, default 0)
     * @param size Page size (default 20, max typically 100)
     * @return HTTP 200 OK with paginated ProductListingResponse list
     */
    @GetMapping
    @Operation(
            summary = "Get product listings by platform",
            description = "Retrieve all listings for a specific platform with pagination. Results sorted by ID (DESC)"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Listings retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    public ResponseEntity<ResponseDTO<Page<ProductListingResponse>>> getByPlatform(
            @RequestParam String platform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProductListingResponse> response = productListingService.getByPlatform(platform, page, size);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Update an existing product listing.
     *
     * Updates platform, platformProductId, and optional references (category, delivery, package).
     * platformProductId uniqueness is validated when changed.
     *
     * @param id Product listing ID
     * @param request CreateProductListingRequest with updated values
     * @return HTTP 200 OK with updated ProductListingResponse
     * @throws IllegalArgumentException if new platformProductId already exists
     * @throws ResourceNotFoundException if listing, category, delivery, or package not found
     */
    @PatchMapping("/{id}")
    @Operation(
            summary = "Update product listing",
            description = "Update an existing product listing. platformProductId uniqueness is validated."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
            responseCode = "200",
            description = "Product listing updated successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Validation error or duplicate platformProductId",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Product listing not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    public ResponseEntity<ResponseDTO<ProductListingResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateProductListingRequest request) {
        ProductListingResponse response = productListingService.update(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Delete a product listing.
     *
     * Removes the listing and all associated options and product compositions.
     *
     * @param id Product listing ID
     * @return HTTP 204 No Content
     * @throws ResourceNotFoundException if listing not found
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete product listing",
            description = "Delete an existing product listing and all associated options"
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
            responseCode = "204",
            description = "Product listing deleted successfully"
    )
    @ApiResponse(
            responseCode = "404",
            description = "Product listing not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class))
    )
    public ResponseEntity<Void> delete(
            @PathVariable Long id) {
        productListingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
