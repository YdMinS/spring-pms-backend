package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 예약 시각 변경 (E8). 본문 {@code {"executeAt":"yyyy-MM-ddTHH:mm:ss"}} — KST 벽시계(D4). */
public record ReservedShipmentTimeRequest(
        @NotNull(message = "예약 시각을 입력하세요")
        LocalDateTime executeAt) {
}
