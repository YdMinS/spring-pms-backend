package com.pms.service;

import com.pms.dto.request.OrderAcknowledgeRequest;

/**
 * Order-confirmation leg: chosen order lines → box dedupe → marketplace order confirmation (paid → preparing) —
 * Coupang acknowledgement and 11st 1634 (FEATURE_2610_17 / D20 · D48).
 *
 * <p>Bulk (list check) and single (order-detail button) use <b>the same method</b> (PLAN 2609_17 D6).
 * Reads only, except the {@code status} of the succeeded boxes, which becomes {@code PREPARING} (D3) —
 * every other field and row belongs to the sync ({@code OrderSyncFacade}).
 *
 * ⚠️ <b>Never call this service automatically</b> (2609_17 D4). The only automatic caller is <b>the run of a
 *    reservation the user made</b> (FEATURE_2609_75 / D6) — the internal-stage branch of [지금 발송] (D27) is a call the
 *    user pressed, not an automatic one. The sync, other schedulers and other services still never call it.
 * ⚠️ Order confirmation <b>cannot be undone</b> — Coupang has no INSTRUCT→ACCEPT API, and after an 11st confirmation the
 *    buyer can no longer cancel alone.
 */
public interface OrderAcknowledgeService {

    /**
     * Groups the boxes of the chosen lines per account, sends them to the marketplace confirmation API (Coupang per
     * box chunk · 11st per delivery number and product number) and sums the results.
     *
     * @param request order_line ids the user checked (1~500)
     * @return expand / send / sum result
     * @throws IllegalArgumentException when not one valid line exists (→ 400)
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
