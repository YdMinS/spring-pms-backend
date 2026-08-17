package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master category (master × platform) response (FEATURE_2608_06 / 13).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master category (master × platform) response")
public class MasterCategoryResponse {

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Category ID", example = "3")
    private Long categoryId;

    @Schema(description = "Category name", example = "신발")
    private String categoryName;
}
