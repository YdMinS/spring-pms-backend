package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.MasterSourceImageRequest;
import com.pms.dto.request.MasterZoneImagesRequest;
import com.pms.dto.request.TagsRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.service.MasterProductImageService;
import com.pms.service.MasterProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Master product definition (CRUD + options) + channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
 *
 * <p>All endpoints are ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no
 * per-method {@code @PreAuthorize}. Tenant scoping is enforced in the service (tenant-filtered reads).</p>
 */
@RestController
@RequestMapping("/api/admin/master-products")
@RequiredArgsConstructor
@Tag(name = "Master Product", description = "Master product + coverage matrix API (ADMIN only)")
public class MasterProductController {

    private final MasterProductService masterProductService;
    private final MasterProductImageService masterProductImageService;

    @GetMapping
    @Operation(summary = "List master products")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductResponse>>> getMasterProducts() {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProducts()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get master product by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> getMasterProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProduct(id)));
    }

    @GetMapping("/{id}/matrix")
    @Operation(summary = "Get channel coverage matrix for a master product")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMatrixResponse>> getMatrix(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMatrix(id)));
    }

    @PostMapping
    @Operation(summary = "Create master product (options included = atomic create)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> createMasterProduct(
            @Valid @RequestBody MasterProductRequest request) {
        MasterProductResponse response = masterProductService.createMasterProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update master product content (name/fieldValues/active/components)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateMasterProduct(
            @PathVariable Long id, @Valid @RequestBody MasterProductUpdateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateMasterProduct(id, request)));
    }

    @PatchMapping("/{id}/tags")
    @Operation(summary = "Replace the master product's tag pool (33; deduped, empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateTags(
            @PathVariable Long id, @Valid @RequestBody TagsRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateTags(id, request.getTags())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete master product (active=false)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteMasterProduct(@PathVariable Long id) {
        masterProductService.deleteMasterProduct(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    @Operation(summary = "Upload a base-image override (sets sourceImageUrl)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> uploadImage(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.uploadMasterImage(id, file)));
    }

    @PostMapping("/{id}/options")
    @Operation(summary = "Create master option (validates component coverage)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> createOption(
            @PathVariable Long id, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.createOption(id, request)));
    }

    @PatchMapping("/{id}/options/{optionId}")
    @Operation(summary = "Update master option (items replace whole set + re-validate)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> updateOption(
            @PathVariable Long id, @PathVariable Long optionId, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateOption(id, optionId, request)));
    }

    @DeleteMapping("/{id}/options/{optionId}")
    @Operation(summary = "Delete master option")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteOption(
            @PathVariable Long id, @PathVariable Long optionId) {
        masterProductService.deleteOption(id, optionId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    // ---------------------------------------------------------------- category (master × platform, 13)

    @PutMapping("/{id}/category")
    @Operation(summary = "Upsert the category for a master × platform")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterCategoryResponse>> upsertCategory(
            @PathVariable Long id, @Valid @RequestBody MasterCategoryRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.upsertCategory(id, request)));
    }

    @GetMapping("/{id}/categories")
    @Operation(summary = "List a master's per-platform categories")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterCategoryResponse>>> getCategories(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getCategories(id)));
    }

    @DeleteMapping("/{id}/categories/{platform}")
    @Operation(summary = "Delete a master's category for a platform")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id, @PathVariable String platform) {
        masterProductService.deleteCategory(id, platform);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- image pool + field mapping (37)

    @PostMapping(value = "/{id}/images", consumes = "multipart/form-data")
    @Operation(summary = "Upload an image into the master's pool")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductImageResponse>> uploadToPool(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        MasterProductImageResponse response = masterProductImageService.uploadToPool(id, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @GetMapping("/{id}/images")
    @Operation(summary = "List the master's pool images (with mapping state)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductImageResponse>>> listPool(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductImageService.listPool(id)));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @Operation(summary = "Remove a pool image (and its mappings)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> removeFromPool(@PathVariable Long id, @PathVariable Long imageId) {
        masterProductImageService.removeFromPool(id, imageId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/zones/{zoneId}/images")
    @Operation(summary = "Set a detail zone's mapped images (ordered; empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductImageResponse>>> setZoneImages(
            @PathVariable Long id, @PathVariable String zoneId,
            @Valid @RequestBody MasterZoneImagesRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                masterProductImageService.setZoneImages(id, zoneId, request.getImageIds())));
    }

    @PutMapping("/{id}/source-image")
    @Operation(summary = "Set (imageId) or clear (null) the master's cover photo")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductImageResponse>> setSourceImage(
            @PathVariable Long id, @RequestBody MasterSourceImageRequest request) {
        MasterProductImageResponse response = masterProductImageService.setSourceImage(id, request.getImageId());
        // imageId != null → 200 with the set cover image; imageId == null (cleared) → 204.
        return response == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ResponseDTO.success(response));
    }
}
