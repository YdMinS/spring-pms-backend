package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 단건 발송처리 요청 — 주문 상세에서 고른 라인 1건 + 사용자가 선택한 택배사·입력한 송장번호.
 *
 * <p>전송 단위는 이 라인이 아니라 <b>이 라인이 속한 박스 전체</b>다(PLAN 2609_11 D1).
 * 택배사는 마켓 코드 자체로 고른다 — 쿠팡은 택배사 목록 API 가 없고 문서의 정적 코드표가 SSOT 라
 * 로컬 {@code carrier} 행이 없는 택배사가 대부분이기 때문(D2 개정 2026-09-03).
 * 임의 문자열은 서버가 {@link com.pms.service.CoupangCourierCodes} 화이트리스트로 거른다.
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record ManualShipmentRequest(
        @NotNull(message = "주문 라인 ID는 필수입니다") Long orderItemId,
        @NotBlank(message = "택배사는 필수입니다")
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber) {
}
