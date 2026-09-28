package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 저장된 송장으로 [예약 발송] (E15, FEATURE_2609_75 / D18 · D20). {@code executeAt} = KST 벽시계(D4).
 * 라인 id 제약은 {@link OrderAcknowledgeRequest} 와 같다.
 */
public record ReservedStoredRequest(
        @NotEmpty(message = "주문 라인을 1건 이상 선택하세요")
        @Size(max = 500, message = "한 번에 500건까지 처리할 수 있습니다")
        List<Long> orderItemIds,
        @NotNull(message = "예약 시각을 입력하세요")
        LocalDateTime executeAt) {
}
