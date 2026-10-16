package com.pms.service;

/**
 * Internal model of one label-sheet row (per product line).
 *
 * ⚠️ Not exposed as a DTO — the output is xlsx bytes only; receiver phone, address and delivery message are never
 * stored in the DB (names are stored in orders, FEATURE_2609_06).
 * Coupang: one box (shipmentBox) × N orderItems flattened 1:N, box-level fields copied to every line.
 * 11st: one preparing-list row = one sheet row (FEATURE_2610_17 / D42).
 *
 * @see ShippingLabelService
 */
public record ShippingLabelRow(
        String receiverName, String receiverPhone, String postCode, String address,
        String productName, int quantity,          // inner quantity (order count)
        int parcelQuantity,                          // parcel count (boxes), default 1
        String vendorItemId,                         // used to build rowKey
        String orderId, String deliveryMessage, String shipmentBoxId,
        String sellerName, String platform) {

    /** Edit matching key: shipmentBoxId + ":" + vendorItemId. */
    public String rowKey() {
        return shipmentBoxId + ":" + vendorItemId;
    }
}
