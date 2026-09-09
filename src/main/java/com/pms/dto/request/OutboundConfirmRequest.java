package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 사람이 확인한 출고 1건 (FEATURE_2609_28 / PLAN D11·D12·D18).
 *
 * <p>라인마다 {@code STOCK_OUT} 행이 하나씩 생긴다 — 작업이 중간에 끊겨도 어디까지 처리했는지
 * 원장에서 복구된다(D12). 부분 확인은 허용이고 남은 수량은 다음에 확인한다.
 *
 * <p>⚠️ 받지 않는 것: {@code reason}({@code STOCK_OUT} 은 컨텍스트가 사유다, D7) ·
 * {@code unitPrice}(출고 원가는 {@code cost_basis} 가 결정한다) · {@code sellerId}(주문에서 유도한다) ·
 * {@code createdBy}(보안 컨텍스트에서 가져온다, D9).
 *
 * <p>🔴 <b>자동 호출 금지</b>(D18) — 발송처리·송장 업로드·동기화 어디에서도 이 요청을 만들지 않는다.
 * 모든 행은 사람의 클릭에서 출발한다.
 */
public record OutboundConfirmRequest(
        @NotNull Long orderLineId,
        @NotEmpty @Valid List<ConfirmLine> lines,
        @NotNull LocalDate movedOn) {

    /** 물품 1건의 확인 수량. 양수로 보내고 서버가 부호를 뒤집는다. */
    public record ConfirmLine(@NotNull Long productId, @NotNull Integer quantity) {
    }
}
