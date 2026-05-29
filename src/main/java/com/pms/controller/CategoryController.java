package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CreateCategoryRequest;
import com.pms.dto.request.UpdateCategoryRequest;
import com.pms.dto.response.CategoryResponse;
import com.pms.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Category management.
 * All endpoints require ADMIN role authorization.
 *
 * Base Path: /api/admin/categories
 * Authentication: Bearer token with ADMIN role required
 *
 * @see CategoryService for business logic
 */
@RestController
@RequestMapping("/api/admin/category")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Category Management", description = "Hierarchical category management APIs")
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * Create a new category.
     *
     * @param request CreateCategoryRequest with category details
     * @return 201 CREATED with CategoryResponse
     */
    @PostMapping
    @Operation(summary = "Create category", description = "Creates new category with optional parent for hierarchy")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Parent category not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CategoryResponse>> createCategory(
        @Valid @RequestBody CreateCategoryRequest request
    ) {
        CategoryResponse response = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ResponseDTO.success(response));
    }

    /**
     * Retrieve all categories, optionally filtered by platform.
     *
     * @param platform Optional platform filter (e.g., "COUPANG", "SMARTSTORE")
     * @return 200 OK with list of CategoryResponse
     */
    @GetMapping
    @Operation(summary = "List categories", description = "Retrieves categories in system, optionally filtered by platform")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<CategoryResponse>>> getCategories(
        @Parameter(description = "Platform filter (optional)", example = "COUPANG")
        @RequestParam(required = false) String platform
    ) {
        List<CategoryResponse> responses = platform != null
            ? categoryService.getCategoriesByPlatform(platform)
            : categoryService.getAllCategories();
        return ResponseEntity.ok(ResponseDTO.success(responses));
    }

    /**
     * Retrieve a specific category by ID.
     *
     * @param id Category ID
     * @return 200 OK with CategoryResponse
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get category by ID", description = "Retrieves specific category details")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Category not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CategoryResponse>> getCategoryById(
        @Parameter(description = "Category ID", required = true)
        @PathVariable Long id
    ) {
        CategoryResponse response = categoryService.getCategoryById(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Update an existing category.
     *
     * @param id Category ID to update
     * @param request UpdateCategoryRequest with updated details
     * @return 200 OK with updated CategoryResponse
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update category", description = "Updates category fields including parent relationship")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error or self-reference", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Category or parent category not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CategoryResponse>> updateCategory(
        @Parameter(description = "Category ID", required = true)
        @PathVariable Long id,
        @Valid @RequestBody UpdateCategoryRequest request
    ) {
        CategoryResponse response = categoryService.updateCategory(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Delete a category.
     * Children's parent_id will become null (orphaned).
     *
     * @param id Category ID to delete
     * @return 200 OK with null data
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete category", description = "Deletes category (children become top-level)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Category not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> deleteCategory(
        @Parameter(description = "Category ID", required = true)
        @PathVariable Long id
    ) {
        categoryService.deleteCategory(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
