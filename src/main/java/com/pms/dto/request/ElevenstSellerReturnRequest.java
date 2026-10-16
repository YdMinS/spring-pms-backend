package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [판매자가 대신 반품 신청] of one 11st order line (FEATURE_2610_17 / D35 · D36 · D39 ⑤ — API 1653).
 * The whole line quantity is returned (1653 has no quantity field).
 *
 * @param reasonCode          one of the 13 reason codes (GET /api/orders/elevenst-return-reasons)
 * @param reasonText          1~200 characters
 * @param shippingFeeMethod   return shipping fee: 03 in the box · 04 sent to the seller
 * @param deliveryCompanyCode optional pickup carrier (11st list) — together with the invoice or not at all
 * @param invoiceNumber       optional pickup invoice — together with the carrier or not at all
 * @param completeNow         true = request and complete the return at once ({@code claimProcess} 02, D36)
 */
public record ElevenstSellerReturnRequest(
        @NotBlank(message = "사유를 선택하세요") String reasonCode,
        @NotBlank(message = "사유는 필수입니다")
        @Size(max = 200, message = "사유는 200자 이하여야 합니다") String reasonText,
        @NotBlank(message = "반품 배송비 전달 방법을 선택하세요") String shippingFeeMethod,
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
        boolean completeNow) {
}
