package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * [발송지연 안내] of an 11st delivery (6728, FEATURE_2610_17 / D63).
 *
 * @param expectedDate expected shipping date — tomorrow ~ today + 30 days (KST)
 * @param reasonCode   {@code delaySendRsnCd} 01~05 (list from {@code GET /api/orders/elevenst-choices})
 * @param reasonText   detail text — 20~300 bytes in EUC-KR (11st −28307); the server counts the bytes
 */
public record ElevenstDeliveryDelayRequest(
        @NotNull(message = "예상 발송일을 고르세요") LocalDate expectedDate,
        @NotBlank(message = "지연 사유를 선택하세요")
        @Size(max = 10, message = "지연 사유 코드는 10자 이하여야 합니다") String reasonCode,
        @NotBlank(message = "상세 사유는 한글 10~150자로 적어 주세요.")
        @Size(max = 300, message = "상세 사유는 한글 10~150자로 적어 주세요.") String reasonText) {
}
