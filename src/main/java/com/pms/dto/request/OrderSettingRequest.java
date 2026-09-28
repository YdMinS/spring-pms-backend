package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 주문관리 설정 저장 요청 (E4). 형식 검사(HH:mm)는 서비스가 한다. */
public record OrderSettingRequest(
        @NotBlank(message = "예약 시각을 입력하세요")
        String reservedShipmentTime) {
}
