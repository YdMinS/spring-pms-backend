package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.FontAssetResponse;
import com.pms.service.FontAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Font registry API (editor dropdown + tenant uploads). All endpoints ADMIN-only via SecurityConfig
 * {@code /api/admin/**}. GET returns system ∪ tenant fonts; DELETE refuses system fonts.
 */
@RestController
@RequestMapping("/api/admin/fonts")
@RequiredArgsConstructor
@Tag(name = "Font Asset", description = "Thumbnail font registry (ADMIN only)")
public class FontAssetController {

    private final FontAssetService fontAssetService;

    @GetMapping
    @Operation(summary = "List fonts (system ∪ tenant)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<FontAssetResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(fontAssetService.list()));
    }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Upload a tenant font (.ttf/.otf, ≤5MB)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<FontAssetResponse>> upload(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(fontAssetService.upload(file)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tenant font (system fonts cannot be deleted)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        fontAssetService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
