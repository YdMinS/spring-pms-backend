package com.pms.service;

/**
 * 송장 접수시트의 한 행(상품 라인 단위) 내부 모델.
 *
 * ⚠️ DTO 로 노출하지 않는다 — 출력은 xlsx bytes 뿐이며, 고객 개인정보(수령인)는 DB 에 저장하지 않는다.
 * box(shipmentBox) 1개 × orderItems N개를 1:N 으로 펼친 결과이며, box 레벨 필드는 모든 라인에 복제된다.
 *
 * @see ShippingLabelService
 */
public record ShippingLabelRow(
        String receiverName, String receiverPhone, String postCode, String address,
        String productName, int quantity,
        String orderId, String deliveryMessage, String shipmentBoxId,
        String sellerName, String platform) {
}
