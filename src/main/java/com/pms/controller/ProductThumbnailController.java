package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ProductThumbnailResponse;
import com.pms.service.ProductThumbnailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Per-product-per-seller thumbnails. All endpoints are ADMIN-only, enforced globally by SecurityConfig
 * ({@code /api/admin/**}) — no per-method {@code @PreAuthorize} (matches ThumbnailTemplate/FontAsset).
 */
@RestController
@RequestMapping("/api/admin/products/{productId}/thumbnails")
@RequiredArgsConstructor
@Tag(name = "Product Thumbnail", description = "Per-product-per-seller thumbnails (ADMIN only)")
public class ProductThumbnailController {

    private final ProductThumbnailService thumbnailService;

    @GetMapping
    @Operation(summary = "List a product's per-seller thumbnails")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ProductThumbnailResponse>>> list(
            @PathVariable Long productId) {
        return ResponseEntity.ok(ResponseDTO.success(thumbnailService.listByProduct(productId)));
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate/regenerate a seller thumbnail (idempotent upsert)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProductThumbnailResponse>> generate(
            @PathVariable Long productId,
            @RequestParam Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(thumbnailService.generate(productId, sellerId)));
    }

    @PostMapping(value = "/{sellerId}/override", consumes = "multipart/form-data")
    @Operation(summary = "Override a seller thumbnail with a manually uploaded image")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProductThumbnailResponse>> override(
            @PathVariable Long productId,
            @PathVariable Long sellerId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(
                thumbnailService.override(productId, sellerId, file)));
    }

    @DeleteMapping("/{sellerId}")
    @Operation(summary = "Delete a seller thumbnail")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> delete(
            @PathVariable Long productId,
            @PathVariable Long sellerId) {
        thumbnailService.delete(productId, sellerId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
