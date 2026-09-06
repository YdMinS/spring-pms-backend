package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 판매상품으로 마스터 프로덕트를 만든 결과(FEATURE_2609_22 / 04). 프론트는 {@code masterProductId} 로 마스터
 * 상세로 이동한다.
 */
@Getter
@Builder
@Schema(description = "Result of creating a master product from a channel cell")
public class ListingMasterCreateResponse {

    @Schema(description = "Created master product ID", example = "88")
    private Long masterProductId;

    @Schema(description = "The channel cell that was linked", example = "312")
    private Long productListingId;

    @Schema(description = "Number of cell options linked to the new master options", example = "2")
    private int optionCount;
}
