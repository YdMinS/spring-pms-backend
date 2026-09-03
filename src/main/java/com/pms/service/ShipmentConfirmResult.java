package com.pms.service;

import java.util.List;

/**
 * 발송처리(송장업로드) 결과 집계 DTO.
 *
 * external*Id 는 저장 시 String 이므로 결과 리포트({@link FailedBox#shipmentBoxId}, {@link #unmatched})도
 * String 을 유지한다(요청 바디만 Long 으로 변환).
 *
 * @param totalRows     파싱된 데이터 행 수(공백행 제외)
 * @param matchedOrders 전송 대상으로 확정된 주문 수 — 스킵분 제외, 폴백 확정분 포함(COUPANG 계정만)
 * @param unmatched     비-COUPANG 이거나, order_item 도 없고 쿠팡 폴백(PLAN 송장시트 D16)으로도 확정하지 못한 orderId
 * @param succeeded     성공 박스 수(응답 responseList succeed=true 집계)
 * @param failed        실패 상세
 * @param skipped       이미 발송된(배송지시 이상) 라인뿐이라 전송하지 않은 주문 (PLAN 2609_07 D1·D7)
 */
public record ShipmentConfirmResult(
        int totalRows,
        int matchedOrders,
        List<String> unmatched,
        int succeeded,
        List<FailedBox> failed,
        List<SkippedOrder> skipped) {

    /** 실패 박스 상세. */
    public record FailedBox(String shipmentBoxId, String resultCode, String message) {
    }

    /**
     * 전송 제외 주문. {@code status} 는 판정에 실제로 쓴 값(쿠팡 코드 원문, 예 {@code DEPARTURE}) —
     * 한글 라벨 변환은 클라이언트 몫이다(PLAN 2609_07 D9). 상태가 다른 박스가 섞였으면 <b>첫 라인의 상태</b>.
     */
    public record SkippedOrder(String orderId, String status) {
    }
}
