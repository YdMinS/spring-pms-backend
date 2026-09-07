package com.pms.service;

import java.util.List;

/**
 * 발송 전 주문 취소 결과 집계 (FEATURE_2609_25 / PLAN D14 · D20).
 *
 * <p>⚠️ 목록 4종이 <b>전부 라인 단위</b>다. 요청이 {@code lines} 배열인데 결과가 주문번호 단위면
 * 여러 라인을 보낼 때 어느 라인이 걸러졌는지 클라이언트가 맞출 수 없다 —
 * 그래서 {@code ShipmentConfirmResult.SkippedOrder} 를 재사용하지 않는다(D20).
 *
 * @param requestedLines 조회에 성공한 라인 수(없는 id 는 세지 않는다)
 * @param succeededLines 취소에 성공한 라인 수
 * @param succeededQty   취소에 성공한 수량 합
 * @param cancelled      성공 라인 상세 — 화면 즉시 갱신용(D14)
 * @param failed         실패 라인 상세 — 쿠팡 원문(D16)
 * @param skipped        상태·전량취소로 전송하지 않은 라인(D2)
 * @param unsupported    비-COUPANG 이거나 박스번호가 없어 전송 불가한 라인(D10)
 */
public record OrderCancelResult(
        int requestedLines,
        int succeededLines,
        int succeededQty,
        List<CancelledLine> cancelled,
        List<FailedLine> failed,
        List<SkippedLine> skipped,
        List<SkippedLine> unsupported) {

    /**
     * 성공한 라인 1건.
     *
     * ⚠️ 취소수량·보류수량을 <b>둘 다</b> 담는다 — {@code receiptType} 이 {@code CANCEL} 이면
     *    {@code cancelCount} 가, {@code STOP_SHIPMENT} 면 {@code holdCount} 가 는다(D7).
     *    하나만 내려주면 상품준비중 취소에서 화면 숫자가 안 바뀌어 성공을 못 알아본다.
     *
     * @param resultStatus 전량취소(cancel+hold ≥ orderQty)면 {@code "CANCELLED"}, 아니면 라인의 중립 상태
     *                     ({@code OrderStatus} 이름) — 클라이언트가 화면 상태 행을 그대로 갱신한다
     */
    public record CancelledLine(Long orderItemId, int cancelledQty,
                                int resultCancelCount, int resultHoldCount,
                                int resultPurchasableQty, String resultStatus,
                                String receiptId, String receiptType) {
    }

    /** 실패한 라인 1건 — 쿠팡 원문 그대로(D16). */
    public record FailedLine(Long orderItemId, String externalItemId, String code, String message) {
    }

    /**
     * 전송하지 않은 라인 1건. {@code reason} = 스킵/불가 사유(한글 1줄).
     * {@code status} 는 중립 상태({@code OrderStatus} 이름) — 라벨 변환은 클라이언트 몫이다.
     */
    public record SkippedLine(Long orderItemId, String externalOrderId, String status, String reason) {
    }
}
