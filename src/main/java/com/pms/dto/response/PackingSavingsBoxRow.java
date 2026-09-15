package com.pms.dto.response;

import com.pms.domain.BoxKind;

import java.math.BigDecimal;

/**
 * 상자별 포장 절약 1행 (FEATURE_2609_41 / PLAN 2609_41 S10).
 *
 * <p>"재활용 상자를 더 모을 가치가 있나"에 답하는 유일한 숫자다.
 *
 * <p>🔴 치수 3개와 {@code imageUrl} 을 함께 싣는다 — 화면이 상자 그림({@code BoxShape})을 그리는 데
 * 필요하다(2609_40 / 04). 빼면 프론트가 상자 목록을 따로 부른다. 치수 0 = 미입력(2609_38 D4),
 * {@code imageUrl} NULL = 사진 없음(도형으로 그린다, 2609_40 D26).
 */
public record PackingSavingsBoxRow(Long packageId,
                                   String type,
                                   BoxKind boxKind,
                                   BigDecimal widthCm,
                                   BigDecimal lengthCm,
                                   BigDecimal heightCm,
                                   String imageUrl,
                                   int parcelCount,
                                   BigDecimal boxSaving,
                                   BigDecimal deliverySaving,
                                   BigDecimal totalSaving) {
}
