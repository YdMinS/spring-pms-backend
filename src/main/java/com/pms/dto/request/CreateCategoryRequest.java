package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new category.
 *
 * Validation rules:
 * - name: Required, max 100 chars
 * - platform: Optional (standard categories carry no platform; codes are owned by CategoryMapping)
 * - platformCategoryId: Optional (see platform)
 * - parentId: Optional (null = top-level category)
 */
public record CreateCategoryRequest(
    @Schema(description = "Category name", example = "Electronics", maxLength = 100)
    @NotBlank(message = "Category name is required")
    String name,

    @Schema(description = "표준 카테고리는 미지정(코드는 CategoryMapping 소유)", maxLength = 50)
    String platform,

    @Schema(description = "표준 카테고리는 미지정(코드는 CategoryMapping 소유)", maxLength = 50)
    String platformCategoryId,

    @Schema(description = "Parent category ID (optional, null for top-level)", example = "1")
    Long parentId
) {}
