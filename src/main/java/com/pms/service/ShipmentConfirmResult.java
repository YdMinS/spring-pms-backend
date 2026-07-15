package com.pms.service;

import java.util.List;

/**
 * 발송처리(송장업로드) 결과 집계 DTO.
 *
 * external*Id 는 저장 시 String 이므로 결과 리포트({@link FailedBox#shipmentBoxId}, {@link #unmatched})도
 * String 을 유지한다(요청 바디만 Long 으로 변환).
 *
 * @param totalRows     파싱된 데이터 행 수(공백행 제외)
 * @param matchedOrders order_item 매칭된 주문 수(COUPANG 계정만)
 * @param unmatched     order_item 이 없거나 비-COUPANG 이라 스킵된 orderId
 * @param succeeded     성공 박스 수(응답 responseList succeed=true 집계)
 * @param failed        실패 상세
 */
public record ShipmentConfirmResult(
        int totalRows,
        int matchedOrders,
        List<String> unmatched,
        int succeeded,
        List<FailedBox> failed) {

    /** 실패 박스 상세. */
    public record FailedBox(String shipmentBoxId, String resultCode, String message) {
    }
}
