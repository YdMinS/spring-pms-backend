package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;
import com.pms.service.ThumbnailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Thumbnail template CRUD + preview. All endpoints are ADMIN-only (enforced by SecurityConfig's
 * {@code /api/admin/**} rules — no per-method {@code @PreAuthorize} needed, matching CarrierRate).
 */
@RestController
@RequestMapping("/api/admin/thumbnail-templates")
@RequiredArgsConstructor
@Tag(name = "Thumbnail Template", description = "Thumbnail template management + preview (ADMIN only)")
public class ThumbnailTemplateController {

    private final ThumbnailTemplateService templateService;

    @PostMapping
    @Operation(summary = "Create thumbnail template")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ThumbnailTemplateResponse>> create(
            @Valid @RequestBody ThumbnailTemplateRequest request) {
        ThumbnailTemplateResponse response = templateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @GetMapping
    @Operation(summary = "List thumbnail templates (tenant-wide library)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ThumbnailTemplateResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(templateService.list()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get thumbnail template by id")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ThumbnailTemplateResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(templateService.get(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update thumbnail template (partial)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ThumbnailTemplateResponse>> update(
            @PathVariable Long id,
            @RequestBody ThumbnailTemplateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(templateService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete thumbnail template")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping(value = "/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    @Operation(summary = "Render a non-persistent preview JPEG (templateId or inline template)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<byte[]> preview(@RequestBody ThumbnailPreviewRequest request) {
        byte[] jpeg = templateService.preview(request);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(jpeg);
    }
}
