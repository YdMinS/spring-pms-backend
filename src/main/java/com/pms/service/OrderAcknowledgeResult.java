package com.pms.service;

import java.util.List;

/**
 * 발주처리 결과 집계.
 *
 * @param requestedLines 조회에 성공한 라인 수 — id dedupe 후 finder 가 돌려준 개수(없는 id 는 세지 않는다)
 * @param targetBoxes    실제 전송한 박스 수(라인 → externalBoxId dedupe, PLAN 2609_17 D1)
 * @param succeeded      성공 박스 수(쿠팡 responseList succeed=true)
 * @param failed         실패 박스 상세 — 쿠팡 resultCode/resultMessage 원문(D15)
 * @param skipped        결제완료가 아니라 전송하지 않은 <b>주문</b>(D2, 라인 아님). status 는 판정에 쓴 원본 쿠팡 코드
 * @param unsupported    비-COUPANG 이거나 externalBoxId 가 없어 전송 불가한 주문번호(D10)
 */
public record OrderAcknowledgeResult(
        int requestedLines,
        int targetBoxes,
        int succeeded,
        List<ShipmentConfirmResult.FailedBox> failed,
        List<ShipmentConfirmResult.SkippedOrder> skipped,
        List<String> unsupported) {
}
