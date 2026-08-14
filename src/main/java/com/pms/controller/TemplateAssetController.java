package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.TemplateAssetResponse;
import com.pms.service.TemplateAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Thumbnail asset library API (editor image picker + tenant uploads). All endpoints ADMIN-only via
 * SecurityConfig {@code /api/admin/**} (no per-method {@code @PreAuthorize}). Assets are tenant-owned
 * ({@code @TenantId}) — list/delete are auto-scoped to the caller's tenant.
 */
@RestController
@RequestMapping("/api/admin/thumbnail-assets")
@RequiredArgsConstructor
@Tag(name = "Template Asset", description = "Thumbnail asset library (ADMIN only)")
public class TemplateAssetController {

    private final TemplateAssetService templateAssetService;

    @GetMapping
    @Operation(summary = "List tenant assets (newest first)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<TemplateAssetResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(templateAssetService.list()));
    }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Upload a tenant asset (jpeg/png)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<TemplateAssetResponse>> upload(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(templateAssetService.upload(file)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tenant asset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        templateAssetService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
