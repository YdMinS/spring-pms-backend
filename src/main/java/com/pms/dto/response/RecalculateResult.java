package com.pms.dto.response;

import java.util.List;

/**
 * 판매가 재계산 결과 (FEATURE_2609_39 / PLAN D7 ①).
 *
 * <p>셀마다 독립 트랜잭션({@code REQUIRES_NEW})이라 하나가 실패해도 나머지는 커밋된다 — 그래서 전체
 * 성공/실패가 아니라 <b>PARTIAL</b> 이 존재한다({@link PropagationApplyResult} 와 같은 계약).</p>
 *
 * <p>🔴 <b>마켓 호출 0회</b>다. 이 결과는 로컬 {@code selling_price} 가 움직였다는 뜻일 뿐이고,
 * 마켓에 걸린 가격({@code market_price})은 그대로다 — 전송은 [마켓 반영]이 따로 한다(D7).</p>
 *
 * @param status        SUCCESS | PARTIAL | FAILED
 * @param cellCount     요청한 셀 수
 * @param optionChanged 판매가가 실제로 달라진 옵션 수(값이 같으면 세지 않는다). 화면이 "몇 개가 움직였는지"를
 *                      먼저 보고 [마켓 반영]으로 넘어간다
 * @param failed        실패한 셀. 나머지 셀은 이미 커밋되었으므로 되돌리지 않는다
 */
public record RecalculateResult(String status, int cellCount, int optionChanged, List<FailedCell> failed) {

    /** 재계산이 끝까지 가지 못한 셀 1건. {@code message} 는 원인 예외의 메시지 그대로다. */
    public record FailedCell(Long listingId, String message) {
    }
}
