package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Category mapping (standard × platform → code) response (FEATURE_2608_06 / 44).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Category mapping (standard × platform → code) response")
public class CategoryMappingResponse {

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Marketplace category code", example = "56137")
    private String platformCategoryId;

    @Schema(description = "Display path (nullable)", example = "패션의류>여성신발>운동화")
    private String platformCategoryName;
}
