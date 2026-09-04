package com.pms.service;

import com.pms.dto.request.OrderAcknowledgeRequest;

/**
 * 발주처리 레그: 선택한 주문 라인 → 박스 dedupe → 쿠팡 발주처리(결제완료→상품준비중) 전송.
 *
 * <p>일괄(목록 체크)과 개별(주문 상세 버튼)이 <b>같은 메서드</b>를 쓴다(PLAN 2609_17 D6).
 * 조회는 읽기 전용. 단, 성공한 박스의 {@code status} 만 {@code INSTRUCT} 로 갱신한다(D3) —
 * 그 외 필드·행은 동기화({@code OrderSyncFacade}) 전담.
 *
 * ⚠️ <b>이 서비스를 자동으로 호출하지 말 것</b>(D4). 동기화·스케줄러·다른 서비스가 부르면
 *    "무엇을 언제 발주할지는 판매자가 정한다"는 결정이 깨진다. 호출자는 컨트롤러 하나뿐이다.
 * ⚠️ 발주처리는 <b>되돌릴 수 없다</b> — 쿠팡에 INSTRUCT→ACCEPT 전환 API 가 없다.
 */
public interface OrderAcknowledgeService {

    /**
     * 선택한 라인들이 속한 박스를 계정별로 묶어 쿠팡 발주처리 API 로 보내고 결과를 집계한다.
     *
     * @param request 사용자가 체크한 order_item id 목록(1~500)
     * @return 전개/전송/집계 결과
     * @throws IllegalArgumentException 유효한 라인이 하나도 없을 때(→ 400)
     */
    OrderAcknowledgeResult acknowledge(OrderAcknowledgeRequest request);
}
