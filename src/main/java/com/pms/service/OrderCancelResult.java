package com.pms.service;

import java.util.List;

/**
 * Seller cancel result before shipping (FEATURE_2609_25 / PLAN D14 · D20).
 *
 * <p>⚠️ All four lists are <b>per line</b>. The request is a {@code lines} array, so per-order results would not let
 * the client tell which line was left out when several lines are sent — that is why
 * {@code ShipmentConfirmResult.SkippedOrder} is not reused (D20).
 *
 * @param requestedLines lines found (ids that do not exist are not counted)
 * @param succeededLines lines cancelled
 * @param succeededQty   quantity cancelled in total (an 11st sale refusal adds 0 — FEATURE_2610_17 / D61)
 * @param cancelled      succeeded line details — for the immediate screen update (D14)
 * @param failed         failed line details — the marketplace's original wording (Coupang D16 · 11st D61)
 * @param skipped        lines not sent because of their status or a full cancel (D2)
 * @param unsupported    lines that cannot be sent — neither COUPANG nor ELEVENST, or no box number (D10 · D61)
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
     * One succeeded line.
     *
     * ⚠️ Holds <b>both</b> the cancel and the hold count — with {@code receiptType} {@code CANCEL} the
     *    {@code cancelCount} grows, with {@code STOP_SHIPMENT} the {@code holdCount} (D7). Sending only one would
     *    leave the screen numbers unchanged after a preparing-order cancel. An 11st sale refusal comes as
     *    {@code ELEVENST_SALE_REFUSAL} with {@code cancelledQty} 0 and the counts unchanged — the next sync moves
     *    them (FEATURE_2610_17 / D61).
     *
     * @param resultStatus {@code "CANCELLED"} after a full cancel (cancel+hold ≥ orderQty), otherwise the line's
     *                     neutral status ({@code OrderStatus} name) — the client updates the status row as is
     */
    public record CancelledLine(Long orderItemId, int cancelledQty,
                                int resultCancelCount, int resultHoldCount,
                                int resultPurchasableQty, String resultStatus,
                                String receiptId, String receiptType) {
    }

    /** One failed line — the marketplace's original wording as given (Coupang D16 · 11st D61). */
    public record FailedLine(Long orderItemId, String externalItemId, String code, String message) {
    }

    /**
     * 전송하지 않은 라인 1건. {@code reason} = 스킵/불가 사유(한글 1줄).
     * {@code status} 는 중립 상태({@code OrderStatus} 이름) — 라벨 변환은 클라이언트 몫이다.
     */
    public record SkippedLine(Long orderItemId, String externalOrderId, String status, String reason) {
    }
}
