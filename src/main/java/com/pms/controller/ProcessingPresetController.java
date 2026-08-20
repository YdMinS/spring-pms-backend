package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ProcessingPresetRequest;
import com.pms.dto.response.ProcessingPresetResponse;
import com.pms.service.ProcessingPresetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Image-processing preset library CRUD (FEATURE_2608_08) — mirror of {@code DetailTemplateController}.
 * Presets are a tenant-wide shared library (no default) referenced from detail templates.
 *
 * <p>ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Reads/writes are tenant-scoped in the service.</p>
 */
@RestController
@RequestMapping("/api/admin/processing-presets")
@RequiredArgsConstructor
@Tag(name = "Processing Preset", description = "Image-processing preset library (ADMIN only)")
public class ProcessingPresetController {

    private final ProcessingPresetService processingPresetService;

    @GetMapping
    @Operation(summary = "List processing presets")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ProcessingPresetResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(processingPresetService.list()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get processing preset by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProcessingPresetResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(processingPresetService.get(id)));
    }

    @PostMapping
    @Operation(summary = "Create processing preset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProcessingPresetResponse>> create(
            @RequestBody ProcessingPresetRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(processingPresetService.create(request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update processing preset (partial)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ProcessingPresetResponse>> update(
            @PathVariable Long id,
            @RequestBody ProcessingPresetRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(processingPresetService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete processing preset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        processingPresetService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
