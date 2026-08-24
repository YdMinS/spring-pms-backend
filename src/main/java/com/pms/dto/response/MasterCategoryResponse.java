package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master standard-category response (FEATURE_2608_06 / 44). Both fields null when unset.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master standard-category response")
public class MasterCategoryResponse {

    @Schema(description = "Standard category ID (null if unset)", example = "3")
    private Long categoryId;

    @Schema(description = "Standard category name (null if unset)", example = "신발")
    private String categoryName;
}
