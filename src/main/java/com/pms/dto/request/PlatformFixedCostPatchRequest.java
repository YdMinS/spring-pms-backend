package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 고정비 카탈로그 항목 부분 수정 (FEATURE_2609_33).
 *
 * <p>🔴 <b>null 필드는 기존 값을 유지</b>한다(프로젝트 PATCH 관례). {@code platform} 은 카탈로그의 축이라
 * 수정 대상이 아니다 — 플랫폼을 바꾸면 이미 걸린 채널 연결이 다른 플랫폼 항목을 가리키게 된다.
 *
 * <p>⚠️ 항목을 없애고 싶으면 {@code active = false} 다. 연결이 남은 항목은 DELETE 가 409 로 막는다.
 */
public record PlatformFixedCostPatchRequest(
        @Size(max = 100) String name,
        @DecimalMin(value = "0", message = "금액은 0 이상이어야 합니다") BigDecimal amount,
        @DecimalMin(value = "0", message = "임계 금액은 0 이상이어야 합니다") BigDecimal thresholdAmount,
        Boolean active
) {
}
