package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * 주문관리 설정 (E3·E4).
 *
 * @param reservedShipmentTime 기본 예약 발송 시각 'HH:mm' (KST)
 * @param nextExecuteAt        그 시각의 다음 도래 시각(KST) — [예약 발송] 입력칸의 기본값(D20)
 */
public record OrderSettingResponse(String reservedShipmentTime, LocalDateTime nextExecuteAt) {
}
