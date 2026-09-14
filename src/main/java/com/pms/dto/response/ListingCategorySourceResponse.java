package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [마스터 카테고리로 변경] 결과 (FEATURE_2609_45 / D13). 프론트가 "A → B 로 변경됩니다" 안내를 만드는 재료다.
 *
 * <p>⚠️ 쿠팡에는 아무것도 보내지 않는다 — 실제 반영은 사용자가 [수정 요청]을 누를 때다.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of switching a cell's category source")
public class ListingCategorySourceResponse {

    @Schema(description = "Product listing ID", example = "12")
    private Long productListingId;

    @Schema(description = "True when the cell now follows the master category", example = "true")
    private boolean useMasterCategory;

    @Schema(description = "Marketplace category code this cell resolves to now", example = "58630")
    private String effectiveCategoryCode;

    @Schema(description = "Channel category code that was cleared (null when there was none)", example = "73170")
    private String previousCategoryCode;
}
