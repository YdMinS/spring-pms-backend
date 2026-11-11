package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [취소 거부] of a LotteON cancel request ([59], FEATURE_2611_23 / D24).
 *
 * @param reasonCode {@code slrRsnCd} — one of {@code cancelRejectReasons} of {@code GET /api/orders/lotteon-choices}
 * @param reasonText optional text, up to 200 characters
 */
public record LotteonCancelRejectRequest(
        @NotBlank(message = "거부 사유를 선택하세요")
        @Size(max = 20, message = "거부 사유 코드는 20자 이하여야 합니다") String reasonCode,
        @Size(max = 200, message = "사유 글은 200자 이하여야 합니다") String reasonText) {
}
