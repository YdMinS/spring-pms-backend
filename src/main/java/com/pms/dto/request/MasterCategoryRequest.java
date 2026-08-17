package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master category upsert request (FEATURE_2608_06 / 13): set the category for a master × platform.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master category (master × platform) upsert request")
public class MasterCategoryRequest {

    @NotBlank(message = "platform is required")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotNull(message = "categoryId is required")
    @Schema(description = "Category ID for this platform", example = "3")
    private Long categoryId;
}
