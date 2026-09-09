package com.pms.service.settlement;

import com.pms.dto.request.CommissionApplyRequest;
import com.pms.dto.response.CommissionApplyResponse;
import com.pms.dto.response.CommissionSuggestionResponse;

import java.time.LocalDate;

/**
 * 실측 수수료율 피드백 — <b>제안 전용</b> (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>정산이 알려준 실제 수수료율로 2019년 정적 기준표를 고칠 기회를 준다. 🔴 <b>자동으로 고치지 않는다</b>:
 * 수수료율은 {@code PriceCalculator} 를 타고 판매가로 파급되고, 실측값은 흔들린다(프로모션 수수료 할인,
 * 카테고리 오분류, 1건짜리 표본). 사람이 보고 확정한다.
 */
public interface CommissionFeedbackService {

    /**
     * 카테고리별 실측 vs 기준표 비교 목록.
     *
     * @param minSamples 이보다 표본이 적은 카테고리는 목록에서 제외(기본 5)
     */
    CommissionSuggestionResponse suggestions(Long sellerId, LocalDate from, LocalDate to, Integer minSamples);

    /** 사용자가 고른 카테고리의 {@code commissionRate} 갱신. 🔴 셀 판매가는 건드리지 않는다. */
    CommissionApplyResponse apply(CommissionApplyRequest request);
}
