package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * [취소 거부] of an 11st cancel request (FEATURE_2610_17 / D38): 11st only rejects a cancel when the goods already
 * left, so it takes the carrier · invoice · date sent. There is no reason field; the delivery method is fixed to
 * parcel on the server.
 *
 * @param deliveryCompanyCode 11st carrier code (the 11st carrier list)
 * @param invoiceNumber       invoice number — more than 10 characters after normalization (11st −23005)
 * @param sendDate            date sent; null = today (KST)
 */
public record ElevenstCancelRejectRequest(
        @NotBlank(message = "택배사를 선택하세요")
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
        LocalDate sendDate) {
}
