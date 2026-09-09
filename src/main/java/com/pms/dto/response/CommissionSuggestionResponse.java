package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 실측 수수료율 제안 목록 (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>🔴 두 목록은 성격이 다르다. {@link #suggestions} 는 <b>교정</b>(기준표에 값이 있는데 실측과 다르다),
 * {@link #seedingGaps} 는 <b>빠진 값 채우기</b>({@code commissionRate = null} — 이 카테고리는 판매가
 * 역산이 아예 400 으로 막힌다). 한 목록에 섞으면 사용자가 무엇을 결정하는지 흐려진다.
 *
 * @param minSamples 이 응답에 적용된 최소 표본 수 — 그보다 적은 카테고리는 제안이 아니라 소음이라 빠졌다
 * @param feeVatRate 비교 기준을 맞추는 데 쓴 수수료 부가세율({@code oclyx.pricing.fee-vat-rate})
 */
public record CommissionSuggestionResponse(
        LocalDate from,
        LocalDate to,
        int minSamples,
        BigDecimal feeVatRate,
        List<CommissionSuggestionView> suggestions,
        List<CommissionSuggestionView> seedingGaps
) {
}
