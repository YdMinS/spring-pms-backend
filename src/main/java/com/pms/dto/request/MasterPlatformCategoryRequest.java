package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master own platform category upsert request (FEATURE_2610_12 / D40) — same fields as
 * {@link CategoryMappingRequest}, so the picker result is sent the same way to both.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master own platform category (master × platform → code) upsert request")
public class MasterPlatformCategoryRequest {

    @NotBlank(message = "platform is required")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "platformCategoryId is required")
    @Schema(description = "Marketplace category code for this platform", example = "56137")
    private String platformCategoryId;

    @Schema(description = "Display path cached at lookup time (optional)", example = "가구>소파>사이드테이블")
    private String platformCategoryName;
}
