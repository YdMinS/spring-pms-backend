package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.CategoryNodeResponse;
import com.pms.dto.response.CategorySuggestionResponse;
import com.pms.service.CategoryLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Category lookup endpoints (FEATURE_2608_06 / 45): tree drill-down + product-name prediction, so the mapping
 * screen (F1) obtains platform codes without hand-typing. ADMIN-only via the global {@code GET /api/admin/**}
 * rule (SecurityConfig) — no per-method {@code @PreAuthorize}. Lookup only (mapping persistence = 44).
 */
@RestController
@RequestMapping("/api/admin/category-lookup")
@RequiredArgsConstructor
@Tag(name = "Category Lookup", description = "Marketplace category tree + prediction (ADMIN only)")
public class CategoryLookupController {

    private final CategoryLookupService categoryLookupService;

    @GetMapping("/{platform}/tree")
    @Operation(summary = "List category tree children (drill-down; parentCode blank = root)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<CategoryNodeResponse>>> tree(
            @PathVariable String platform,
            @RequestParam(required = false) String parentCode,
            @RequestParam(required = false) Long sellerId) {
        List<CategoryNodeResponse> nodes = categoryLookupService.browse(platform, parentCode, sellerId).stream()
                .map(CategoryNodeResponse::from)
                .toList();
        return ResponseEntity.ok(ResponseDTO.success(nodes));
    }

    @GetMapping("/{platform}/predict")
    @Operation(summary = "Predict category candidates from a product name (0~N)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<CategorySuggestionResponse>>> predict(
            @PathVariable String platform,
            @RequestParam String productName,
            @RequestParam(required = false) Long sellerId) {
        List<CategorySuggestionResponse> suggestions =
                categoryLookupService.predict(platform, productName, sellerId).stream()
                        .map(CategorySuggestionResponse::from)
                        .toList();
        return ResponseEntity.ok(ResponseDTO.success(suggestions));
    }
}
