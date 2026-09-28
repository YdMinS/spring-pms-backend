package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 내부 단계 배송 묶음의 택배사·송장번호 저장 (E12, FEATURE_2609_75 / D18). 제약은 단건 발송처리
 * {@link ManualShipmentRequest} 와 같다 — 택배사 코드는 서버가 코드표로 검증하고 송장번호는 하이픈·공백을 지운다.
 */
public record ReservedInvoiceRequest(
        @NotBlank(message = "택배사는 필수입니다")
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber) {
}
