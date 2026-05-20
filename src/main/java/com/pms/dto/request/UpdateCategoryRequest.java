package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for updating an existing category.
 *
 * Validation rules:
 * - name: Required, max 100 chars
 * - platform: Required, max 50 chars
 * - platformCategoryId: Required, max 50 chars
 * - parentId: Optional (null = top-level category, can be changed)
 *
 * Note: Category ID cannot be changed and cannot equal parentId (self-reference prevention)
 */
public record UpdateCategoryRequest(
    @Schema(description = "Updated category name", example = "Electronics", maxLength = 100)
    @NotBlank(message = "Category name is required")
    String name,

    @Schema(description = "Updated platform identifier", example = "AMAZON", maxLength = 50)
    @NotBlank(message = "Platform is required")
    String platform,

    @Schema(description = "Updated external platform category ID", example = "cat-12345", maxLength = 50)
    @NotBlank(message = "Platform category ID is required")
    String platformCategoryId,

    @Schema(description = "Updated parent category ID (optional, null for top-level)", example = "2")
    Long parentId
) {}
