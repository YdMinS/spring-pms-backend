package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 발주처리 요청 — 사용자가 목록에서 체크한 주문 라인 id 들(PLAN 2609_17 D6·D7).
 *
 * <p>전송 단위는 이 라인들이 아니라 <b>이 라인들이 속한 박스</b>다(D1) — 서버가 externalBoxId 로 dedupe 한다.
 * 개별 발주처리도 같은 DTO 로 온다(길이 1).
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record OrderAcknowledgeRequest(
        @NotEmpty(message = "주문 라인을 1건 이상 선택하세요")
        @Size(max = 500, message = "한 번에 500건까지 처리할 수 있습니다")
        List<Long> orderItemIds) {
}
