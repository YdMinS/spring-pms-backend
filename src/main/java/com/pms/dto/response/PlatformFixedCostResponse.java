package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 고정비 카탈로그 한 줄 (FEATURE_2609_33 / PLAN 2609_33 D1).
 *
 * @param amount          🔴 부가세 <b>포함</b> 월 정액(D3) — 화면이 여기에 세금을 더하지 않는다
 * @param thresholdAmount 카탈로그 기본 임계. 채널이 {@code thresholdOverride} 로 덮는다(D2-1)
 * @param active          {@code false} = 계산에서 완전히 빠진다(과거 달도 세지 않는다)
 */
public record PlatformFixedCostResponse(
        Long id,
        String platform,
        String name,
        BigDecimal amount,
        BigDecimal thresholdAmount,
        boolean active
) {
}
