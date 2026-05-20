package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new category.
 *
 * Validation rules:
 * - name: Required, max 100 chars
 * - platform: Required, max 50 chars
 * - platformCategoryId: Required, max 50 chars
 * - parentId: Optional (null = top-level category)
 */
public record CreateCategoryRequest(
    @Schema(description = "Category name", example = "Electronics", maxLength = 100)
    @NotBlank(message = "Category name is required")
    String name,

    @Schema(description = "Platform identifier", example = "AMAZON", maxLength = 50)
    @NotBlank(message = "Platform is required")
    String platform,

    @Schema(description = "External platform category ID", example = "cat-12345", maxLength = 50)
    @NotBlank(message = "Platform category ID is required")
    String platformCategoryId,

    @Schema(description = "Parent category ID (optional, null for top-level)", example = "1")
    Long parentId
) {}
