package com.pms.service;

import com.pms.dto.request.OrderCancelRequest;
import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * Seller cancel before shipping: chosen lines × quantity → account · order · box groups → the Coupang cancel API
 * (FEATURE_2609_25); an 11st line goes to the 11st sale refusal 1638 of the whole line instead (FEATURE_2610_17 /
 * D61).
 *
 * <p>Coupang: paid (ACCEPT) becomes an <b>instant cancel</b>, preparing (INSTRUCT) a <b>shipment stop</b> — which one
 * the Coupang response's {@code receiptType} tells, and the local write-back column follows it (PLAN 2609_25 D7).
 * 11st: the line is not written here — the next sync reads the cancel (D14).
 *
 * ⚠️ <b>Never call this service automatically</b> (D12). If the sync, a scheduler or another service called it, the
 *    decision "the seller decides what to cancel" would break. The only caller is the controller.
 * ⚠️ A cancel <b>cannot be undone</b> and lowers the marketplace seller score (Coupang order fulfilment · 11st credit
 *    score unless the reason is 「고객변심」) — the confirm dialog is the screen contract (D13 · D61).
 * ⚠️ Not in one class with order confirmation ({@link OrderAcknowledgeService}) — that one is box unit without
 *    quantity, this one option unit with quantity (11st: the whole line), and the grouping rules would mix.
 */
public interface OrderCancelService {

    /**
     * Groups the chosen Coupang lines by account → order → box and sends them to the Coupang cancel API; 11st lines go
     * one by one to the 11st sale refusal (1638, whole line — FEATURE_2610_17 / D61). Results are collected per line.
     *
     * @param request lines × quantity (1~50) and the reason (Coupang {@code reason} · 11st {@code reasonCode} +
     *                {@code reasonText})
     * @return the four per-line result lists (cancelled / failed / skipped / unsupported)
     * @throws IllegalArgumentException duplicate line id · no valid line · quantity over the cancellable count ·
     *                                  missing reason (→ 400, before sending)
     */
    OrderCancelResult cancel(OrderCancelRequest request);

    /**
     * 화면에 보일 취소 사유 목록 (D4). 서버가 목록의 유일한 소유자이며 요청 검증도 같은 enum 을 쓴다 —
     * 클라이언트에 코드→라벨 상수를 두지 않는다.
     */
    List<ActionChoice> availableReasons();
}
