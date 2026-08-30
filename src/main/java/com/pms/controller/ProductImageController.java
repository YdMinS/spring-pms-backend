package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ProductImageReorderRequest;
import com.pms.dto.response.ProductImageResponse;
import com.pms.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * A product's image gallery (1:N). All endpoints are ADMIN-only, enforced globally by SecurityConfig
 * ({@code /api/admin/**} covers POST/GET/PUT/DELETE) — no per-method {@code @PreAuthorize} (matches
 * ProductThumbnailController). See FEATURE_2608_06 / 39.
 *
 * <p>⚠️ {@code PUT /images/reorder} (literal) and {@code PUT /images/{imageId}} (replace) coexist —
 * Spring matches the literal path over the {@code {imageId}} variable, so reorder never leaks into replace.</p>
 */
@RestController
@RequestMapping("/api/admin/products/{productId}/images")
@RequiredArgsConstructor
@Tag(name = "Product Image Gallery", description = "A product's 1:N source image gallery (ADMIN only)")
public class ProductImageController {

    private final ProductImageService productImageService;

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Add one or more images to the product's gallery")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ProductImageResponse>>> addImages(
            @PathVariable Long productId,
            @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.ok(ResponseDTO.success(productImageService.addImages(productId, files)));
    }

    @GetMapping
    @Operation(summary = "List the product's gallery images (in order)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ProductImageResponse>>> list(@PathVariable Long productId) {
        return ResponseEntity.ok(ResponseDTO.success(productImageService.list(productId)));
    }

    @PutMapping(value = "/{imageId}", consumes = "multipart/form-data")
    @Operation(summary = "Replace one gallery image in place (keeps the same image id)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProductImageResponse>> replaceImage(
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(
                productImageService.replaceImage(productId, imageId, file)));
    }

    @PutMapping("/reorder")
    @Operation(summary = "Reorder the gallery to exactly this ordered set of image ids")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ProductImageResponse>>> reorder(
            @PathVariable Long productId,
            @RequestBody ProductImageReorderRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                productImageService.reorder(productId, request.getImageIds())));
    }

    @DeleteMapping("/{imageId}")
    @Operation(summary = "Delete one gallery image")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {
        productImageService.deleteImage(productId, imageId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
