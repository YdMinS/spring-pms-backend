package com.pms.service;

import java.util.List;

/**
 * Result of a single (manual) shipment confirm.
 *
 * @param orderId        marketplace order number (Coupang orderId · 11st ordNo)
 * @param shipmentBoxId  the box that was sent (the unit an invoice attaches to, PLAN 2609_11 D1) — 11st = delivery
 *                       number {@code dlvNo} (FEATURE_2610_17 / D2)
 * @param mode           "CREATE" (new upload) | "UPDATE" (invoice update) — the server decides by status (D3); 11st is
 *                       always "CREATE" (no update API, D21); LotteON = "CREATE" before shipping and "UPDATE" for
 *                       the invoice update of the chosen records (FEATURE_2611_23 / D19)
 * @param sentLines      Coupang: dto count sent = option lines of the box · 11st: order lines of the delivery number,
 *                       or the chosen lines of a {@code PARTIAL} / {@code SPLIT} send (FEATURE_2610_17 / D64)
 * @param succeeded      Coupang: responseList succeed=true count · 11st: 1 when the delivery number was accepted
 * @param failed         failure details (the marketplace's own wording, D6) — reuses {@link ShipmentConfirmResult.FailedBox}
 * @param resultStatus   local status after success ({@code "SHIPPED"} on a CREATE success, else null) — the client uses
 *                       it as is to refresh the screen (D4). 🔴 It is the neutral status (FEATURE_2609_26 / PLAN D4) —
 *                       sending the raw value (DEPARTURE) would show the old label only right after the refresh
 * @param records        LotteON invoice update only — one outcome per chosen record, in request order
 *                       (FEATURE_2611_23 / D19 ⑥-7); every other path = empty list
 */
public record ManualShipmentResult(String orderId, String shipmentBoxId, String mode, int sentLines,
                                   int succeeded, List<ShipmentConfirmResult.FailedBox> failed,
                                   String resultStatus, List<RecordOutcome> records) {

    /** Every path but the LotteON invoice update — no record outcomes. */
    public ManualShipmentResult(String orderId, String shipmentBoxId, String mode, int sentLines, int succeeded,
                                List<ShipmentConfirmResult.FailedBox> failed, String resultStatus) {
        this(orderId, shipmentBoxId, mode, sentLines, succeeded, failed, resultStatus, List.of());
    }

    /**
     * One LotteON record of the invoice update (FEATURE_2611_23 / D19 ⑥-7).
     *
     * @param oldInvoiceNumber the invoice of the re-read [140] record (null when it was blank or the record was not
     *                         found / not pickable)
     * @param message          LotteON's own text or the not-found text on failure; null on success
     */
    public record RecordOutcome(Long orderItemId, String procSeq, String oldInvoiceNumber, boolean succeeded,
                                String message) {
    }
}
