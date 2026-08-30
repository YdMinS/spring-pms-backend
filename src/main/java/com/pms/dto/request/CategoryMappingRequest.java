package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Category mapping upsert request (FEATURE_2608_06 / 44): the platform code for a standard category × platform.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Category mapping (standard × platform → code) upsert request")
public class CategoryMappingRequest {

    @NotBlank(message = "platform is required")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "platformCategoryId is required")
    @Schema(description = "Marketplace category code for this platform", example = "56137")
    private String platformCategoryId;

    @Schema(description = "Display path cached at lookup time (optional)", example = "패션의류>여성신발>운동화")
    private String platformCategoryName;
}
