package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;
import com.pms.service.CategoryMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Category mapping (standard × platform → marketplace code) CRUD (FEATURE_2608_06 / 44).
 *
 * <p>All endpoints are ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}.</p>
 */
@RestController
@RequestMapping("/api/admin/category-mappings")
@RequiredArgsConstructor
@Tag(name = "Category Mapping", description = "Standard category × platform → code mapping API (ADMIN only)")
public class CategoryMappingController {

    private final CategoryMappingService categoryMappingService;

    @GetMapping("/categories/{categoryId}/mappings")
    @Operation(summary = "List a standard category's per-platform mappings")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<CategoryMappingResponse>>> getMappings(@PathVariable Long categoryId) {
        return ResponseEntity.ok(ResponseDTO.success(categoryMappingService.getMappings(categoryId)));
    }

    @PutMapping("/categories/{categoryId}/mappings")
    @Operation(summary = "Upsert a standard category's mapping for a platform")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CategoryMappingResponse>> upsertMapping(
            @PathVariable Long categoryId, @Valid @RequestBody CategoryMappingRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(categoryMappingService.upsertMapping(categoryId, request)));
    }

    @DeleteMapping("/categories/{categoryId}/mappings/{platform}")
    @Operation(summary = "Delete a standard category's mapping for a platform")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> deleteMapping(@PathVariable Long categoryId, @PathVariable String platform) {
        categoryMappingService.deleteMapping(categoryId, platform);
        return ResponseEntity.noContent().build();
    }
}
