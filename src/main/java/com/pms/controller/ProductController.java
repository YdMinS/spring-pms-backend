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
}
