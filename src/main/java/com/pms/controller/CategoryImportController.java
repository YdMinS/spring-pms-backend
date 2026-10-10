package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.CategoryImportResult;
import com.pms.dto.response.ElevenstCategoryImportResult;
import com.pms.dto.response.ElevenstFeeImportResult;
import com.pms.service.category.CategoryImportService;
import com.pms.service.category.ElevenstCategoryImportService;
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
 * Marketplace category bulk-import — admin only. Coupang xlsx (FEATURE_2608_06 / 53) · 11st public tree and
 * 11st fee notice page (FEATURE_2610_10 / D21 ① · D22).
 *
 * <p>ADMIN-only via the global {@code POST /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Idempotent upsert (not a destructive re-seed); a confirm gate belongs to the
 * front-end / ops runbook. Large files are processed synchronously per top-level file; call once per file.</p>
 */
@RestController
@RequestMapping("/api/admin/category-import")
@RequiredArgsConstructor
@Tag(name = "Category Import", description = "Marketplace category bulk import (ADMIN only)")
public class CategoryImportController {

    private final CategoryImportService categoryImportService;
    private final ElevenstCategoryImportService elevenstCategoryImportService;

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

    @PostMapping("/elevenst")
    @Operation(summary = "Import the public 11st category tree into platform_category (no file)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstCategoryImportResult>> importElevenst() {
        return ResponseEntity.ok(ResponseDTO.success(elevenstCategoryImportService.importTree()));
    }

    @PostMapping(value = "/elevenst-fee", consumes = "multipart/form-data")
    @Operation(summary = "Import 11st leaf commissions from the saved seller-office fee notice page")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstFeeImportResult>> importElevenstFee(
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ResponseDTO.success(elevenstCategoryImportService.importFees(file.getBytes())));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded category file", e);
        }
    }
}
