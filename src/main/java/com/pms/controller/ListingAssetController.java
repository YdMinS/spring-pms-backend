package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DetailHtmlOverrideRequest;
import com.pms.dto.request.DisplayNameRequest;
import com.pms.dto.request.FieldValuesRequest;
import com.pms.dto.request.ShippingOverrideRequest;
import com.pms.dto.request.TagsRequest;
import com.pms.dto.response.DetailPreviewResponse;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.dto.response.GeneratedProductResponse;
import com.pms.service.ListingAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Channel-cell auto-generated assets (FEATURE_2608_06 / 3b-2). ADMIN-only via the global
 * {@code /api/admin/**} rule (SecurityConfig) — no per-method {@code @PreAuthorize}. The cell is
 * tenant-scoped in the service (a cross-tenant/absent id → 404).
 */
@RestController
@RequestMapping("/api/admin/product-listings")
@RequiredArgsConstructor
@Tag(name = "Listing Assets", description = "Channel-cell thumbnail/detail/price generation (ADMIN only)")
public class ListingAssetController {

    private final ListingAssetService listingAssetService;

    @PostMapping("/{id}/regenerate")
    @Operation(summary = "Regenerate a cell's thumbnail + detail + option prices (backfill consumer)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> regenerate(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.regenerate(id)));
    }

    @GetMapping("/{id}/generated")
    @Operation(summary = "Read a cell's generated assets (404 if not yet generated)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> getGenerated(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.getGenerated(id)));
    }

    @GetMapping("/{id}/detail-preview")
    @Operation(summary = "Non-persistent AUTO detail-HTML preview (ignores any override, for comparison)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailPreviewResponse>> detailPreview(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.previewDetail(id)));
    }

    @GetMapping("/{id}/detail-template")
    @Operation(summary = "Resolved detail template for this cell (account-assigned ?? tenant default)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailTemplateResponse>> resolvedDetailTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.resolveDetailTemplate(id)));
    }

    @PutMapping("/{id}/detail-html")
    @Operation(summary = "Override the cell's detail HTML (source=MANUAL_OVERRIDE)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> overrideDetailHtml(
            @PathVariable Long id, @Valid @RequestBody DetailHtmlOverrideRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.overrideDetailHtml(id, request.getHtml())));
    }

    @DeleteMapping("/{id}/detail-html")
    @Operation(summary = "Drop the detail-HTML override (source=AUTO) and re-apply generator output")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> clearDetailHtml(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.clearDetailHtml(id)));
    }

    @PostMapping(value = "/{id}/thumbnail", consumes = "multipart/form-data")
    @Operation(summary = "Override a cell's thumbnail with an uploaded image (thumbnailSource=MANUAL_OVERRIDE)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> overrideThumbnail(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.overrideThumbnail(id, file)));
    }

    @DeleteMapping("/{id}/thumbnail")
    @Operation(summary = "Drop the thumbnail override (thumbnailSource=AUTO) and re-render")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> clearThumbnail(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.clearThumbnail(id)));
    }

    @PatchMapping("/{id}/field-values")
    @Operation(summary = "Override this cell's text field values, then regenerate assets (empty map clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> updateFieldValues(
            @PathVariable Long id, @Valid @RequestBody FieldValuesRequest request) {
        return ResponseEntity.ok(
                ResponseDTO.success(listingAssetService.updateFieldValues(id, request.getFieldValues())));
    }

    @PatchMapping("/{id}/tags")
    @Operation(summary = "Replace this cell's raw channel tags (33; deduped, empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> updateTags(
            @PathVariable Long id, @Valid @RequestBody TagsRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.updateTags(id, request.getTags())));
    }

    @PatchMapping("/{id}/name")
    @Operation(summary = "Update this cell's display name (노출상품명 = listing name; internal only, no push)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> updateDisplayName(
            @PathVariable Long id, @Valid @RequestBody DisplayNameRequest request) {
        listingAssetService.updateDisplayName(id, request.getName());
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PatchMapping("/{id}/shipping-override")
    @Operation(summary = "Replace this cell's channel shipping overrides (75; whitelist-filtered, empty clears; no regenerate)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> updateShippingOverride(
            @PathVariable Long id, @RequestBody ShippingOverrideRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                listingAssetService.updateShippingOverride(id, request.getOverride())));
    }

}
