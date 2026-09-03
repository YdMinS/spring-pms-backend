package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 단건 발송처리 요청 — 주문 상세에서 고른 라인 1건 + 사용자가 선택한 택배사·입력한 송장번호.
 *
 * <p>전송 단위는 이 라인이 아니라 <b>이 라인이 속한 박스 전체</b>다(PLAN 2609_11 D1).
 * 마켓 코드(deliveryCompanyCode)는 서버가 {@code carrierId} 로 해석한다 — 클라이언트는 모른다(D2).
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record ManualShipmentRequest(
        @NotNull(message = "주문 라인 ID는 필수입니다") Long orderItemId,
        @NotNull(message = "택배사는 필수입니다") Long carrierId,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber) {
}
