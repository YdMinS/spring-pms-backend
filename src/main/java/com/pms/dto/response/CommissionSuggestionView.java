package com.pms.dto.response;

import com.pms.domain.Platform;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 카테고리 1건의 "기준표 vs 실측" 비교 행 (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>🔴 <b>두 비율의 기준이 같다</b>: {@link #currentRatio} 는 기준표 수수료율에 부가세를 얹은 값이고
 * {@link #measuredRatio} 는 쿠팡이 실제로 뗀 (수수료 + 그 부가세) 비율이다. 기준을 맞추지 않고 빼면
 * 부가세 10% 가 통째로 "수수료율 차이"로 잡혀 전 카테고리가 제안 목록에 오른다.
 *
 * <p>⚠️ {@link #suggestedRate} 는 <b>실제로 저장될 값</b>이다({@code commission_rate} 는
 * {@code DECIMAL(5,2)} 이라 소수점 둘째 자리가 한계 = 해상도 1%p). 화면은 반올림 전 실측
 * {@link #measuredRate} 를 같이 보여줘야 사용자가 "0.6%p 차이인데 왜 1%p 오르나"를 알 수 있다.
 *
 * @param currentRate   기준표 값(부가세 <b>별도</b>) — 시드 누락이면 null
 * @param currentRatio  {@code currentRate × (1 + 수수료 부가세율)} — 시드 누락이면 null
 * @param measuredRatio 실측 = {@code Σ(수수료 + 수수료 부가세) / Σ 판매금액} (가중평균)
 * @param measuredRate  실측을 부가세 별도로 되돌린 값 = {@code measuredRatio ÷ (1 + 부가세율)}
 * @param suggestedRate 확정 시 저장될 값 = {@code measuredRate} 를 소수점 2자리로 반올림한 것
 * @param gap           {@code measuredRatio − currentRatio} — 시드 누락이면 null
 * @param impact        {@code |gap| × 판매금액} = 정렬 기준(금액 영향이 큰 것부터)
 * @param samples       집계에 들어간 라인 수(환불 라인 제외)
 * @param listingCount  이 카테고리로 팔린 서로 다른 셀 수 — 확정 시 "영향받는 셀" 안내용
 */
public record CommissionSuggestionView(
        Long platformCategoryId,
        String platformCategoryCode,
        String platformCategoryName,
        Platform platform,
        BigDecimal currentRate,
        BigDecimal currentRatio,
        BigDecimal measuredRatio,
        BigDecimal measuredRate,
        BigDecimal suggestedRate,
        BigDecimal gap,
        BigDecimal impact,
        int samples,
        int listingCount,
        BigDecimal saleAmount,
        LocalDate periodFrom,
        LocalDate periodTo
) {
}
