package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CollagePreviewRequest;
import com.pms.dto.request.CollageSaveRequest;
import com.pms.dto.response.CollageOverviewResponse;
import com.pms.dto.response.CollageStageResponse;
import com.pms.service.collage.CollageStage;
import com.pms.service.collage.ThumbnailCollageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Thumbnail collage stages of a master (FEATURE_2610_21). ADMIN-only via the global {@code /api/admin/**} rule
 * (SecurityConfig) — no per-method {@code @PreAuthorize}. Stage = query {@code stage=MASTER|SELLER|LISTING} +
 * {@code sellerId} (SELLER) / {@code listingId} (LISTING).
 */
@RestController
@RequestMapping("/api/admin/master-products/{masterId}/collage")
@RequiredArgsConstructor
@Tag(name = "Thumbnail Collage", description = "Collage per master / seller / listing stage (ADMIN only)")
public class ThumbnailCollageController {

    private final ThumbnailCollageService thumbnailCollageService;

    @GetMapping
    @Operation(summary = "Every collage stage of the master and what each currently uses")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CollageOverviewResponse>> overview(@PathVariable Long masterId) {
        return ResponseEntity.ok(ResponseDTO.success(thumbnailCollageService.overview(masterId)));
    }

    @GetMapping("/stage")
    @Operation(summary = "One collage stage for the editor (adjusted to the current components)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CollageStageResponse>> getStage(
            @PathVariable Long masterId,
            @RequestParam CollageStage stage,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long listingId) {
        return ResponseEntity.ok(ResponseDTO.success(
                thumbnailCollageService.getStage(masterId, stage, sellerId, listingId)));
    }

    @PutMapping("/stage")
    @Operation(summary = "Save this stage's own collage, or switch the collage off here (no regeneration)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CollageStageResponse>> save(
            @PathVariable Long masterId,
            @RequestParam CollageStage stage,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long listingId,
            @RequestBody CollageSaveRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                thumbnailCollageService.save(masterId, stage, sellerId, listingId, request)));
    }

    @DeleteMapping("/stage")
    @Operation(summary = "Delete this stage's own collage (the stage then uses the upper stage)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CollageStageResponse>> delete(
            @PathVariable Long masterId,
            @RequestParam CollageStage stage,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long listingId) {
        return ResponseEntity.ok(ResponseDTO.success(
                thumbnailCollageService.delete(masterId, stage, sellerId, listingId)));
    }

    @PostMapping(value = "/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    @Operation(summary = "Render a non-persistent collage thumbnail JPEG")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<byte[]> preview(@PathVariable Long masterId, @RequestBody CollagePreviewRequest request) {
        byte[] jpeg = thumbnailCollageService.preview(masterId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(jpeg);
    }
}
