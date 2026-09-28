package com.pms.service;

import com.pms.dto.request.OrderAcknowledgeRequest;

/**
 * 발주처리 레그: 선택한 주문 라인 → 박스 dedupe → 쿠팡 발주처리(결제완료→상품준비중) 전송.
 *
 * <p>일괄(목록 체크)과 개별(주문 상세 버튼)이 <b>같은 메서드</b>를 쓴다(PLAN 2609_17 D6).
 * 조회는 읽기 전용. 단, 성공한 박스의 {@code status} 만 {@code INSTRUCT} 로 갱신한다(D3) —
 * 그 외 필드·행은 동기화({@code OrderSyncFacade}) 전담.
 *
 * ⚠️ <b>이 서비스를 자동으로 호출하지 말 것</b>(2609_17 D4). 자동 호출의 예외는 <b>사용자가 만든 예약 발송의 실행</b>(FEATURE_2609_75 / D6)뿐이다 —
 *    [지금 발송]의 내부 단계 분기(D27)는 사용자가 누른 즉시 호출이라 자동 호출이 아니다. 동기화·다른 스케줄러·다른 서비스는 여전히 부르지 않는다.
 * ⚠️ 발주처리는 <b>되돌릴 수 없다</b> — 쿠팡에 INSTRUCT→ACCEPT 전환 API 가 없다.
 */
public interface OrderAcknowledgeService {

    /**
     * 선택한 라인들이 속한 박스를 계정별로 묶어 쿠팡 발주처리 API 로 보내고 결과를 집계한다.
     *
     * @param request 사용자가 체크한 order_line id 목록(1~500)
     * @return 전개/전송/집계 결과
     * @throws IllegalArgumentException 유효한 라인이 하나도 없을 때(→ 400)
     */
    OrderAcknowledgeResult acknowledge(OrderAcknowledgeRequest request);

    /**
     * 발주처리 → 송장 등록 순서 경로의 ② (FEATURE_2609_75 / D6 · D27). {@link #acknowledge} 와 같은 분류·청크·write-back 을 타고,
     * 화면 [발주처리] 경로의 「처리 중」 차단·예약 해제는 하지 않는다(호출자가 스스로 판정한다).
     * 호출자는 둘이다 — {@code ReservedShipmentExecutor}(예약 실행 ②) · {@code ShipmentConfirmServiceImpl} 의 [지금 발송]
     * 내부 단계 분기(D27). ❌ 주문 동기화·다른 스케줄에서 부르지 말 것.
     */
    ReservationAckResult acknowledgeForReservation(java.util.List<Long> orderLineIds);
}
