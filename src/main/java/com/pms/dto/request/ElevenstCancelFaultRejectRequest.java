package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * [책임사유 오류로 거부] of an 11st cancel request (1745, FEATURE_2610_17 / D62): refuses a seller-fault cancel the
 * buyer filed on a paid line. 11st takes every field, so the delivery fields are always required — same fields and
 * rules as [취소 거부] (D38), delivery method fixed to parcel on the server.
 *
 * @param refuseCode          {@code ordCnRefsRsnCd} 01 · 02 (list from {@code GET /api/orders/elevenst-choices})
 * @param reasonText          reason sent to the buyer, 1~200 characters (D39 ①)
 * @param deliveryCompanyCode 11st carrier code (the 11st carrier list)
 * @param invoiceNumber       invoice number — more than 10 characters after normalization (11st −23005)
 * @param sendDate            date sent; null = today (KST)
 */
public record ElevenstCancelFaultRejectRequest(
        @NotBlank(message = "거부 사유를 선택하세요")
        @Size(max = 10, message = "거부 사유 코드는 10자 이하여야 합니다") String refuseCode,
        @NotBlank(message = "사유는 필수입니다")
        @Size(max = 200, message = "사유는 200자 이하여야 합니다") String reasonText,
        @NotBlank(message = "택배사를 선택하세요")
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
        LocalDate sendDate) {
}
