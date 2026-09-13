package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 지금 담긴 조합으로 상자 후보를 묻는다 (FEATURE_2609_40 / PLAN D22 · D23).
 *
 * <p>포장 화면은 물건을 담을 때마다 이 요청을 보낸다 — 조합이 바뀌면 추천도 바뀌기 때문이다.
 *
 * <p>⚠️ 주문·옵션·판매자를 담지 않는다: 기억표의 키는 <b>물품 × 수량</b> 뿐이다(D22).
 */
public record BoxCandidateRequest(@NotEmpty @Valid List<CandidateItem> items) {

    public record CandidateItem(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }
}
