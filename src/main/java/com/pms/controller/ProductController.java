package com.pms.controller;

import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.dto.common.ResponseDTO;
import com.pms.service.ProductService;
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
 * ProductController - REST API endpoints for Product management
 *
 * Phase 2-1 TDD CREATE: Only POST /api/products endpoint
 * Future phases will add:
 * - Phase 2-2: GET /api/products/{id}
 * - Phase 2-3: GET /api/products (with pagination/search)
 * - Phase 2-4: PATCH /api/products/{id}
 * - Phase 2-5: DELETE /api/products/{id}
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Product", description = "Product management API")
public class ProductController {

    private final ProductService productService;

    /**
     * Create a new product (ADMIN only)
     *
     * @param request CreateProductRequest with product details
     * @return HTTP 201 Created with ProductResponse
     */
    @PostMapping
    @Operation(summary = "Create product", description = "Create a new product (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Product created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    /**
     * Get product by ID
     *
     * @param id Product ID
     * @return HTTP 200 OK with ProductResponse
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Retrieve a product by its ID")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Product found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<ProductResponse>> getProduct(@PathVariable(name = "id") Long id) {
        ProductResponse response = productService.getProduct(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Get all products with pagination and search
     *
     * @param page Page number (0-indexed)
     * @param size Page size (default 20)
     * @param search Search keyword
     * @return HTTP 200 OK with Page of ProductResponse
     */
    @GetMapping
    @Operation(summary = "Get all products", description = "Retrieve all products with pagination and search")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Products retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Page<ProductResponse>>> getAllProducts(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "search", required = false) String search) {
        Page<ProductResponse> response = productService.getAllProducts(page, size, search);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }
}
