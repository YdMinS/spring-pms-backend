package com.pms.controller;

import com.pms.domain.Platform;
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
 * - GET /{id} : Get listing by ID
 * - GET ?platform=... : Get listings by platform (paginated)
 * - PATCH /{id} : Update listing (ADMIN)
 * - DELETE /{id} : Delete listing (ADMIN)
 *
 * 🔴 2609_71/D7: 셀 직접 등록(POST)은 없다 — 판매상품은 마스터를 통해서만 생긴다
 * (마스터 상세의 [채널 추가] · 쿠팡 상품 ID 편입 · 쿠팡 ID 로 마스터 생성).
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
     * @param masterLinked 마스터 연결 여부 필터(2609_22/04); 미지정 = 전체(기존 동작)
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
            @RequestParam(defaultValue = "20") int size,
            // 3값(미지정/true/false)이라 boolean 으로 받지 말 것 — 미지정이 곧 "필터 없음"이다.
            @RequestParam(required = false) Boolean masterLinked) {
        Page<ProductListingResponse> response =
                productListingService.getByPlatform(Platform.from(platform), page, size, masterLinked);
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
