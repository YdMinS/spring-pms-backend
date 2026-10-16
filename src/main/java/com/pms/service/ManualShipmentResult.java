package com.pms.service;

import java.util.List;

/**
 * Result of a single (manual) shipment confirm.
 *
 * @param orderId        marketplace order number (Coupang orderId · 11st ordNo)
 * @param shipmentBoxId  the box that was sent (the unit an invoice attaches to, PLAN 2609_11 D1) — 11st = delivery
 *                       number {@code dlvNo} (FEATURE_2610_17 / D2)
 * @param mode           "CREATE" (new upload) | "UPDATE" (invoice update) — the server decides by status (D3); 11st is
 *                       always "CREATE" (no update API, D21)
 * @param sentLines      Coupang: dto count sent = option lines of the box · 11st: order lines of the delivery number,
 *                       or the chosen lines of a {@code PARTIAL} / {@code SPLIT} send (FEATURE_2610_17 / D64)
 * @param succeeded      Coupang: responseList succeed=true count · 11st: 1 when the delivery number was accepted
 * @param failed         failure details (the marketplace's own wording, D6) — reuses {@link ShipmentConfirmResult.FailedBox}
 * @param resultStatus   local status after success ({@code "SHIPPED"} on a CREATE success, else null) — the client uses
 *                       it as is to refresh the screen (D4). 🔴 It is the neutral status (FEATURE_2609_26 / PLAN D4) —
 *                       sending the raw value (DEPARTURE) would show the old label only right after the refresh
 */
public record ManualShipmentResult(String orderId, String shipmentBoxId, String mode, int sentLines,
                                   int succeeded, List<ShipmentConfirmResult.FailedBox> failed,
                                   String resultStatus) {
}
