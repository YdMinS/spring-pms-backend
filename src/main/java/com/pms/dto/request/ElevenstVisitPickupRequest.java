package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [방문수령 완료] of an 11st visit-pickup order (7052, FEATURE_2610_17 / D66).
 *
 * @param certCode the visit-pickup code the buyer gave — sent as typed, trimmed only (no format check)
 */
public record ElevenstVisitPickupRequest(
        @NotBlank(message = "방문수령 인증번호를 입력하세요")
        @Size(max = 50, message = "방문수령 인증번호는 50자 이하여야 합니다") String certCode) {
}
