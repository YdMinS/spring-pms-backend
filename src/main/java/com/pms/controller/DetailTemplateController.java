package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DetailTemplateRequest;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.service.DetailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Detail-page template library CRUD (FEATURE_2608_06 / 17) — mirror of {@code ThumbnailTemplateController}.
 * Templates are a tenant-wide shared library with a single default; block editing supports the new
 * {@code spacer} block.
 *
 * <p>ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Reads/writes are tenant-scoped in the service.</p>
 */
@RestController
@RequestMapping("/api/admin/detail-templates")
@RequiredArgsConstructor
@Tag(name = "Detail Template", description = "Detail-page template library (ADMIN only)")
public class DetailTemplateController {

    private final DetailTemplateService detailTemplateService;

    @GetMapping
    @Operation(summary = "List detail templates")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<DetailTemplateResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.list()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get detail template by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailTemplateResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.get(id)));
    }

    @PostMapping
    @Operation(summary = "Create detail template")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailTemplateResponse>> create(
            @RequestBody DetailTemplateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.create(request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update detail template (partial)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailTemplateResponse>> update(
            @PathVariable Long id,
            @RequestBody DetailTemplateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(detailTemplateService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete detail template")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        detailTemplateService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
