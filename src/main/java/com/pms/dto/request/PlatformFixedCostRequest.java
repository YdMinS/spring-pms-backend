package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 고정비 카탈로그 항목 생성 (FEATURE_2609_33 / PLAN 2609_33 D1 · D3).
 *
 * @param platform        `Platform` enum 이름. 카탈로그의 축이다 — 항목은 플랫폼마다 다르다
 * @param amount          🔴 월 정액, <b>부가세 포함</b> 값 그대로(D3). 여기에 세금을 또 곱하지 않는다
 * @param thresholdAmount 부과 임계. 비우면 {@code 1,000,000}(쿠팡 일반 카테고리 기준, D2-1)
 */
public record PlatformFixedCostRequest(
        @NotBlank(message = "플랫폼이 비어 있습니다") String platform,
        @NotBlank(message = "항목명이 비어 있습니다") @Size(max = 100) String name,
        @NotNull(message = "금액이 없습니다") @DecimalMin(value = "0", message = "금액은 0 이상이어야 합니다")
        BigDecimal amount,
        @DecimalMin(value = "0", message = "임계 금액은 0 이상이어야 합니다") BigDecimal thresholdAmount
) {
}
