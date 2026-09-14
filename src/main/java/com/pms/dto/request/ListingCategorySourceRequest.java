package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [마스터 카테고리로 변경] 토글 (FEATURE_2609_45 / D13).
 *
 * <p>{@code true} = 이 셀이 마스터 카테고리를 따르게 한다(채널 카테고리 코드·고시 품목군·셀 옵션 메타를
 * 모두 비운다). {@code false} = 400 — 채널 카테고리는 가져오기로만 설정되므로 되돌릴 원본이 없다.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Switch a channel cell back to the master's category")
public class ListingCategorySourceRequest {

    @NotNull
    @Schema(description = "true = follow the master category (false is rejected with 400)", example = "true")
    private Boolean useMasterCategory;
}
