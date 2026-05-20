package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for category information.
 * Contains all fields of a Category entity for API responses.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryResponse {

    @Schema(description = "Unique identifier (auto-generated)", example = "1")
    private Long id;

    @Schema(description = "Category name", example = "Electronics", maxLength = 100)
    private String name;

    @Schema(description = "Platform identifier", example = "AMAZON", maxLength = 50)
    private String platform;

    @Schema(description = "External platform category ID", example = "cat-12345", maxLength = 50)
    private String platformCategoryId;

    @Schema(description = "Parent category ID (null for top-level categories)", example = "1")
    private Long parentId;

    @Schema(description = "Creation timestamp (immutable)", example = "2026-05-20T10:30:00", format = "date-time")
    private LocalDateTime createdDate;

    @Schema(description = "Last modification timestamp", example = "2026-05-20T10:30:00", format = "date-time")
    private LocalDateTime modifiedDate;
}
