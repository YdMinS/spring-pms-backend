package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 주문 최신화 요청 — 사용자가 목록·상세에서 체크한 주문 라인 id 들(FEATURE_2609_50 / D1).
 *
 * <p>조회 단위는 이 라인들이 아니라 <b>이 라인들이 속한 주문번호</b>다 — 서버가 externalOrderId 로
 * dedupe 한다. 개별 최신화도 같은 DTO 로 온다(길이 1).
 *
 * <p>🔴 {@code @Size} 를 붙이지 않는다 — 상한은 라인이 아니라 <b>주문 수</b> 기준이라(D3)
 * dedupe 이후에야 알 수 있다. 서비스가 dedupe 후 검사하고 {@code IllegalArgumentException} 을 던진다.
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record OrderRefreshRequest(
        @NotEmpty(message = "주문을 1건 이상 선택하세요")
        List<Long> orderItemIds) {
}
