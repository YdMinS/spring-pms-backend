package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 채널에 걸린 고정비 항목 한 줄 (FEATURE_2609_33 / PLAN 2609_33 D2 · D2-1 · D5).
 *
 * @param fixedCostId       카탈로그 항목 id — 🔴 연결 행 id 가 아니다. replace(PUT)가 이 id 로 오간다
 * @param amount            카탈로그 금액(참고 표시용). 채널에 복사돼 있지 않다(D1)
 * @param thresholdAmount   <b>실효</b> 임계 = {@code thresholdOverride ?? 카탈로그 기본값}
 * @param thresholdOverride 채널이 실제로 덮은 값. null 이면 화면이 "카탈로그 기본값"으로 표시한다
 */
public record AccountFixedCostResponse(
        Long fixedCostId,
        String name,
        BigDecimal amount,
        String chargeMode,
        BigDecimal thresholdAmount,
        BigDecimal thresholdOverride,
        String appliedFrom,
        String appliedTo
) {
}
