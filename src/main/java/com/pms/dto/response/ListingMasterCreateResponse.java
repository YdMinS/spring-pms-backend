package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 판매상품으로 마스터 프로덕트를 만든 결과(FEATURE_2609_22 / 04). 프론트는 {@code masterProductId} 로 마스터
 * 상세로 이동한다.
 *
 * <p>FEATURE_2609_45 가 "마켓 상품으로 마스터 만들기"에서 <b>그대로 재사용</b>한다(필드 3개가 이미 같다) —
 * 같은 모양의 DTO 를 새로 만들지 말 것.</p>
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

    /** 2609_45: 마켓에서 읽어온 셀의 상태. 2609_22 경로(셀이 이미 있음)에서는 채우지 않는다(null). */
    @Schema(description = "The channel cell's status; null when created from an existing cell", example = "SELLING")
    private ListingStatus status;
}
