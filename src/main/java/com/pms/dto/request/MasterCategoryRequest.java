package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master standard-category set request (FEATURE_2608_06 / 44): assign the master's single standard category.
 * The per-platform marketplace code is resolved from the master's own platform category (FEATURE_2610_12) or else
 * {@link com.pms.domain.CategoryMapping}, not here.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master standard-category set request")
public class MasterCategoryRequest {

    @NotNull(message = "categoryId is required")
    @Schema(description = "Standard category ID", example = "3")
    private Long categoryId;
}
