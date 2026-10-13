package com.pms.controller;

import com.pms.domain.Platform;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.CategoryMetaSchemaResponse;
import com.pms.dto.response.CategoryNodeResponse;
import com.pms.dto.response.CategorySuggestionResponse;
import com.pms.service.CategoryLookupService;
import com.pms.service.CategoryMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Category lookup endpoints (FEATURE_2608_06 / 45): tree drill-down + product-name prediction + name search, so the mapping
 * screen (F1) obtains platform codes without hand-typing. ADMIN-only via the global {@code GET /api/admin/**}
 * rule (SecurityConfig) — no per-method {@code @PreAuthorize}. Lookup only (mapping persistence = 44).
 */
@RestController
@RequestMapping("/api/admin/category-lookup")
@RequiredArgsConstructor
@Tag(name = "Category Lookup", description = "Marketplace category tree + prediction (ADMIN only)")
public class CategoryLookupController {

    private final CategoryLookupService categoryLookupService;
    private final CategoryMetaService categoryMetaService;

    @GetMapping("/{platform}/tree")
    @Operation(summary = "List category tree children (drill-down; parentCode blank = root)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<CategoryNodeResponse>>> tree(
            @PathVariable String platform,
            @RequestParam(required = false) String parentCode,
            @RequestParam(required = false) Long sellerId) {
        List<CategoryNodeResponse> nodes = categoryLookupService.browse(Platform.from(platform), parentCode, sellerId).stream()
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
                categoryLookupService.predict(Platform.from(platform), productName, sellerId).stream()
                        .map(CategorySuggestionResponse::from)
                        .toList();
        return ResponseEntity.ok(ResponseDTO.success(suggestions));
    }

    @GetMapping("/{platform}/search")
    @Operation(summary = "Search imported leaf categories by name (up to 50, with name path; no account needed)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<CategorySuggestionResponse>>> search(
            @PathVariable String platform,
            @RequestParam String keyword) {
        List<CategorySuggestionResponse> results =
                categoryLookupService.search(Platform.from(platform), keyword).stream()
                        .map(CategorySuggestionResponse::from)
                        .toList();
        return ResponseEntity.ok(ResponseDTO.success(results));
    }

    /**
     * FEATURE_2610_12 / D45: {@code platformCategoryCode} (a 「개별 지정」 pick on the master add screen) wins
     * over {@code categoryId}; without it the standard category id is required (400 「카테고리 없음」).
     */
    @GetMapping("/{platform}/meta")
    @Operation(summary = "Category meta schema (required attributes + notices) by category id or platform "
            + "category code; empty allowed")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CategoryMetaSchemaResponse>> meta(
            @PathVariable String platform,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String platformCategoryCode) {
        Platform target = Platform.from(platform);
        if (platformCategoryCode != null && !platformCategoryCode.isBlank()) {
            return ResponseEntity.ok(ResponseDTO.success(CategoryMetaSchemaResponse.from(
                    categoryMetaService.getSchemaByPlatformCategoryCode(platformCategoryCode.trim(), target))));
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("카테고리 없음");
        }
        CategoryMetaSchemaResponse schema =
                CategoryMetaSchemaResponse.from(categoryMetaService.getSchema(categoryId, target));
        return ResponseEntity.ok(ResponseDTO.success(schema));
    }
}
