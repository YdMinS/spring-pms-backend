package com.pms.dto.response;

/**
 * One order line of an 11st delivery, for 「일부 줄만 보내기」 · 「한 줄을 송장 여러 장으로」 (FEATURE_2610_17 / D64).
 *
 * @param orderItemId order line id (what the shipment request sends back)
 * @param itemName    item name as stored
 * @param orderQty    order quantity (the split quantities add up to this)
 * @param status      neutral status — only {@code PREPARING} lines can be sent
 * @param addOn       11st add-on line ({@code addPrdYn} Y) — add-on lines alone cannot be sent partially
 */
public record ElevenstDeliveryLineResponse(Long orderItemId, String itemName, int orderQty, String status,
                                           boolean addOn) {
}
