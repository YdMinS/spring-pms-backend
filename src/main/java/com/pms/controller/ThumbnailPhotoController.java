package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ThumbnailPhotoRequest;
import com.pms.dto.response.ThumbnailPhotoStageView;
import com.pms.service.ThumbnailPhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Thumbnail photo stages of a master (FEATURE_2610_32 D23). ADMIN-only via the global {@code /api/admin/**} rule
 * (SecurityConfig) — no per-method {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin/master-products/{masterId}/thumbnail-photo")
@RequiredArgsConstructor
@Tag(name = "Thumbnail Photo", description = "Thumbnail photo per master / seller / listing stage (ADMIN only)")
public class ThumbnailPhotoController {

    private final ThumbnailPhotoService thumbnailPhotoService;

    @GetMapping
    @Operation(summary = "Every thumbnail photo stage of the master and what each currently uses")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ThumbnailPhotoStageView>>> stages(@PathVariable Long masterId) {
        return ResponseEntity.ok(ResponseDTO.success(thumbnailPhotoService.stages(masterId)));
    }

    @PutMapping
    @Operation(summary = "Choose (imageId) or clear (null) one stage's thumbnail photo (no regeneration)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ThumbnailPhotoStageView>>> update(
            @PathVariable Long masterId, @RequestBody ThumbnailPhotoRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(thumbnailPhotoService.update(masterId, request)));
    }
}
