package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master own platform category response (FEATURE_2610_12 / D40) — same fields as
 * {@link CategoryMappingResponse}.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master own platform category (master × platform → code) response")
public class MasterPlatformCategoryResponse {

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Marketplace category code", example = "56137")
    private String platformCategoryId;

    @Schema(description = "Display path (nullable)", example = "가구>소파>사이드테이블")
    private String platformCategoryName;

    @Schema(description = "FEATURE_2610_34 / D20: new Coupang registrations of this category leave 총 수량 out",
            example = "false")
    private boolean grandTotalOmitted;
}
