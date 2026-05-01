package com.pms.controller;

import com.pms.config.ImageStorageProperties;
import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.dto.common.ResponseDTO;
import com.pms.exception.ImageNotFoundException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import com.pms.service.ImageStorageService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final ImageStorageService imageStorageService;
    private final ProductRepository productRepository;
    private final ImageStorageProperties imageStorageProperties;

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
     * Check if barcode already exists
     *
     * @param barcodeId Barcode ID to check
     * @return HTTP 200 OK with exists flag
     */
    @GetMapping("/check-barcode")
    @Operation(summary = "Check barcode existence", description = "Check if a barcode is already registered")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Check completed",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<java.util.Map<String, Boolean>>> checkBarcodeExists(
            @RequestParam(name = "barcode") String barcodeId) {
        boolean exists = productRepository.existsByBarcodeId(barcodeId);
        java.util.Map<String, Boolean> result = java.util.Collections.singletonMap("exists", exists);
        return ResponseEntity.ok(ResponseDTO.success(result));
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

    /**
     * Update a product (ADMIN only)
     *
     * @param id Product ID
     * @param request UpdateProductRequest with fields to update
     * @return HTTP 200 OK with updated ProductResponse
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update product", description = "Update a product (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Product updated successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<ProductResponse>> updateProduct(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductResponse response = productService.updateProduct(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Delete a product (soft delete, ADMIN only)
     *
     * @param id Product ID
     * @return HTTP 200 OK with success message
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete product", description = "Soft delete a product (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Product deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> deleteProduct(@PathVariable(name = "id") Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /**
     * Upload product image (ADMIN only)
     *
     * @param id Product ID
     * @param file Image file (multipart/form-data)
     * @return HTTP 200 OK with success message
     */
    @PutMapping("/{id}/image")
    @Operation(summary = "Upload product image", description = "Upload image for a product (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Image uploaded successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid image file",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<ProductResponse>> uploadImage(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "file") MultipartFile file) {

        // 1. Find product by ID
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // 2. Check product is active
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // 3. Upload image via service
        String imageFilename = imageStorageService.uploadImage(file, id);

        // 4. Update product with new imageUrl (full path)
        String imageUrl = imageStorageProperties.getUploadDir() + "/" + imageFilename;
        Product updatedProduct = product.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .price(product.getPrice())
                .productName(product.getProductName())
                .store(product.getStore())
                .unit(product.getUnit())
                .volumeHeight(product.getVolumeHeight())
                .volumeLong(product.getVolumeLong())
                .volumeShort(product.getVolumeShort())
                .weight(product.getWeight())
                .description(product.getDescription())
                .name(product.getName())
                .imageUrl(imageUrl)
                .active(product.getActive())
                .build();

        productRepository.save(updatedProduct);

        // 5. Return 200 OK with updated product
        return ResponseEntity.ok(ResponseDTO.success(ProductResponse.of(updatedProduct)));
    }

    /**
     * Get product image (ADMIN only)
     *
     * @param id Product ID
     * @return HTTP 200 OK with image content
     */
    @GetMapping("/{id}/image")
    @Operation(summary = "Get product image", description = "Retrieve image for a product")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Image retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Image not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<?> getImage(@PathVariable(name = "id") Long id) {

        // 1. Find product by ID
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // 2. Check if product is active
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // 3. Check if product has image URL
        if (product.getImageUrl() == null || product.getImageUrl().isEmpty()) {
            return ResponseEntity.notFound().build(); // 404
        }

        // 4. Retrieve image bytes
        try {
            byte[] imageBytes = imageStorageService.getImage(product.getImageUrl());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(imageBytes.length)
                    .body(imageBytes);
        } catch (java.io.FileNotFoundException e) {
            return ResponseEntity.notFound().build(); // 404
        }
    }

    /**
     * Delete product image (ADMIN only)
     *
     * @param id Product ID
     * @return HTTP 200 OK with success message
     */
    @DeleteMapping("/{id}/image")
    @Operation(summary = "Delete product image", description = "Delete image for a product (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Image deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<ProductResponse>> deleteImage(@PathVariable(name = "id") Long id) {

        // 1. Find product by ID
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // 2. Check product is active
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // 3. Delete image file (graceful)
        if (product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
            imageStorageService.deleteImage(product.getImageUrl());
        }

        // 4. Update product to clear imageUrl
        Product updatedProduct = product.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .price(product.getPrice())
                .productName(product.getProductName())
                .store(product.getStore())
                .unit(product.getUnit())
                .volumeHeight(product.getVolumeHeight())
                .volumeLong(product.getVolumeLong())
                .volumeShort(product.getVolumeShort())
                .weight(product.getWeight())
                .description(product.getDescription())
                .name(product.getName())
                .imageUrl(null)  // Clear image URL
                .active(product.getActive())
                .build();

        productRepository.save(updatedProduct);

        // 5. Return 200 OK with updated product
        return ResponseEntity.ok(ResponseDTO.success(ProductResponse.of(updatedProduct)));
    }
}
