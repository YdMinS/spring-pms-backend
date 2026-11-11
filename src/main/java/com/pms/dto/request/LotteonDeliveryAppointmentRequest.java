package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * [발송 약정일 안내] of a LotteON order ([138], FEATURE_2611_23 / D26).
 *
 * @param appointmentDate promised shipping date — sent as that day {@code 235959}; no range check here (R25)
 * @param reasonCode      {@code sndDlRsnCd} — one of {@code appointmentReasons} of {@code GET /api/orders/lotteon-choices}
 * @param detailCode      one of {@code appointmentDetailReasons}; required for {@code PD_DL}, ignored otherwise
 */
public record LotteonDeliveryAppointmentRequest(
        @NotNull(message = "발송 약정일을 고르세요") LocalDate appointmentDate,
        @NotBlank(message = "지연 사유를 선택하세요")
        @Size(max = 20, message = "지연 사유 코드는 20자 이하여야 합니다") String reasonCode,
        @Size(max = 20, message = "상세 사유 코드는 20자 이하여야 합니다") String detailCode) {
}
