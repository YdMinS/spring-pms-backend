package com.pms.service;

import com.pms.dto.request.OrderCancelRequest;
import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * 발송 전 주문 취소 레그: 선택한 라인×수량 → 계정·주문·박스 그룹 → 쿠팡 취소 API 전송 (FEATURE_2609_25).
 *
 * <p>결제완료(ACCEPT)는 <b>즉시취소</b>, 상품준비중(INSTRUCT)은 <b>출고중지</b>가 된다 — 어느 쪽인지는
 * 쿠팡 응답의 {@code receiptType} 이 알려주고, 그 값에 따라 로컬 write-back 컬럼이 갈린다(PLAN 2609_25 D7).
 *
 * ⚠️ <b>이 서비스를 자동으로 호출하지 말 것</b>(D12). 동기화·스케줄러·다른 서비스가 부르면 "무엇을
 *    취소할지는 판매자가 정한다"는 결정이 깨진다. 호출자는 컨트롤러 하나뿐이다.
 * ⚠️ 취소는 <b>되돌릴 수 없고</b> 쿠팡 판매자 점수(주문이행)를 떨어뜨린다 — 확인 다이얼로그가 화면 계약이다(D13).
 * ⚠️ 발주처리({@link OrderAcknowledgeService})와 한 클래스에 두지 않는다 — 그쪽은 박스 단위·수량 없음,
 *    여기는 옵션 단위·수량 있음이라 그룹핑 규칙이 섞인다.
 */
public interface OrderCancelService {

    /**
     * 선택한 라인들을 계정→주문→박스로 묶어 쿠팡 취소 API 로 보내고 결과를 집계한다.
     *
     * @param request 취소할 라인×수량 목록(1~50)과 사유
     * @return 라인 단위 결과 4종(cancelled/failed/skipped/unsupported)
     * @throws IllegalArgumentException 라인 id 중복 · 유효한 라인 없음 · 취소 가능 수량 초과(→ 400, 전송 전)
     */
    OrderCancelResult cancel(OrderCancelRequest request);

    /**
     * 화면에 보일 취소 사유 목록 (D4). 서버가 목록의 유일한 소유자이며 요청 검증도 같은 enum 을 쓴다 —
     * 클라이언트에 코드→라벨 상수를 두지 않는다.
     */
    List<ActionChoice> availableReasons();
}
