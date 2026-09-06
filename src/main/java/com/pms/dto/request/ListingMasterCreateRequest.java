package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매상품으로 마스터 프로덕트 생성 요청(FEATURE_2609_22 / 04).
 *
 * <p>⚠️ 옵션·가격·구성·옵션 id 는 <b>일부러 받지 않는다</b>. 전부 셀과 쿠팡 재조회에서 서버가 확정한다
 * (클라이언트 값 신뢰 금지 — 02 와 같은 규칙). 사용자가 정하는 것은 마스터명과 표준 카테고리뿐이다.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create a master product from an unlinked channel cell")
public class ListingMasterCreateRequest {

    /** 기본값 = 미리보기의 {@code suggestedMasterName}(D25). 사용자가 수정할 수 있다. */
    @NotBlank(message = "masterName is required")
    @Schema(description = "Master product name", example = "노브랜드 생수 2L")
    private String masterName;

    /** D26: 프리필(역조회) 또는 사용자 선택. 마스터는 표준 카테고리 없이 존재할 수 없으므로 필수다. */
    @NotNull(message = "categoryId is required")
    @Schema(description = "Standard category ID", example = "41")
    private Long categoryId;
}
