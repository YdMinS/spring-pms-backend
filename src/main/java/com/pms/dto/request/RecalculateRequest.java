package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 판매가 재계산 요청 (FEATURE_2609_39 / PLAN D7 ① · D11 · D21).
 *
 * <p>🔴 단위는 <b>셀</b>이다. 화면의 선택 단위는 옵션이지만(D21) 재계산 이음매
 * ({@code ListingAssetService.recalculateOptionPrices})의 단위가 셀이라, 고른 옵션이 속한 <b>고유 셀 목록</b>
 * 으로 접어서 보낸다 — 그 셀의 AUTO 옵션이 <b>전부</b> 다시 계산된다(고르지 않은 옵션 포함).</p>
 *
 * <p>⚠️ 상한은 이 DTO 가 소유한다(서비스에서 다시 세지 않는다). 한 요청이 전 판매자의 전 상품을 건드리면
 * 값 하나가 틀렸을 때 피해가 전부다(D11).</p>
 */
public record RecalculateRequest(
        @NotEmpty(message = "재계산할 상품을 선택하세요")
        @Size(max = RecalculateRequest.MAX_CELLS,
                message = "한 번에 최대 " + RecalculateRequest.MAX_CELLS + "개 상품까지 재계산할 수 있습니다 — 나눠 실행하세요")
        List<Long> listingIds) {

    /** 한 요청의 셀 상한(D11). 마켓 반영의 상한은 축이 달라 따로 있다({@link RepricePushRequest}). */
    public static final int MAX_CELLS = 200;
}
