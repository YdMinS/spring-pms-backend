package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.CategoryImportResult;
import com.pms.service.category.CategoryImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Coupang category bulk-import (FEATURE_2608_06 / 53) — admin only.
 *
 * <p>ADMIN-only via the global {@code POST /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Idempotent upsert (not a destructive re-seed); a confirm gate belongs to the
 * front-end / ops runbook. Large files are processed synchronously per top-level file; call once per file.</p>
 */
@RestController
@RequestMapping("/api/admin/category-import")
@RequiredArgsConstructor
@Tag(name = "Category Import", description = "Coupang category xlsx bulk import (ADMIN only)")
public class CategoryImportController {

    private final CategoryImportService categoryImportService;

    @PostMapping(value = "/coupang", consumes = "multipart/form-data")
    @Operation(summary = "Import a Coupang category xlsx (PlatformCategory tree + oclyx mirror + mappings)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CategoryImportResult>> importCoupang(
            @RequestParam("file") MultipartFile file) {
        try {
            CategoryImportResult result = categoryImportService.importCoupang(file.getInputStream());
            return ResponseEntity.ok(ResponseDTO.success(result));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded category file", e);
        }
    }
}
