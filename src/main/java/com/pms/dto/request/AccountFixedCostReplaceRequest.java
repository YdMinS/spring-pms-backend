package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 채널의 고정비 연결 <b>멱등 replace</b> (FEATURE_2609_33 / PLAN 2609_33 D2 · D2-1 · D5).
 *
 * <p>🔴 보낸 목록이 그 채널의 <b>전부</b>다 — 빠진 항목은 연결이 끊긴다. 부분 추가/삭제 엔드포인트를
 * 따로 두지 않는다(2609_30 {@code setZoneImages} 미러).
 *
 * <p>🔴 항목은 <b>그 채널의 플랫폼</b>에 속한 것만 받는다 — 다른 플랫폼 항목이 섞이면 400 이다.
 * 카탈로그의 축이 플랫폼인데 채널이 그것을 넘어서면 목록과 집계가 서로 다른 답을 낸다.
 */
public record AccountFixedCostReplaceRequest(
        @Valid List<Item> items
) {

    /**
     * @param chargeMode        {@code AUTO} · {@code ALWAYS} · {@code NEVER}(D2). 모르는 값이면 400
     * @param thresholdOverride null = 카탈로그 임계 사용(D2-1)
     * @param appliedFrom       {@code YYYY-MM}. null = 전 기간(D5)
     * @param appliedTo         {@code YYYY-MM}. null = 진행 중(D5)
     */
    public record Item(
            @NotNull(message = "고정비 항목 id 가 없습니다") Long fixedCostId,
            @NotNull(message = "부과 방식이 없습니다") String chargeMode,
            BigDecimal thresholdOverride,
            String appliedFrom,
            String appliedTo
    ) {
    }
}
