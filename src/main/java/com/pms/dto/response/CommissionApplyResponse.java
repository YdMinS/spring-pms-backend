package com.pms.dto.response;

/**
 * 수수료율 확정 반영 결과 (FEATURE_2609_30 / 06 · PLAN D16 · D19-1).
 *
 * <p>🔴 {@link #notice} 가 이 응답의 요점이다 — 수수료율이 바뀌어도 <b>기존 셀 판매가는 그대로다</b>.
 * 반영은 원가/가격 반영 화면({@code /api/admin/cost/propagation/preview → apply})이 소유하며, 사용자가
 * 트리거할 때까지 판매가는 움직이지 않는다(PLAN 2609_28 D4).
 *
 * @param updated           실제로 값이 바뀐 카테고리 수
 * @param unchanged         요청에 있었지만 저장 결과가 현재 값과 같아 건너뛴 수(반올림으로 같아지는 경우 포함)
 * @param affectedListings  기간 내 이 카테고리로 팔린 서로 다른 셀 수(안내용 추정치 — 판매 이력이 없는
 *                          셀은 세지 않는다)
 */
public record CommissionApplyResponse(
        int updated,
        int unchanged,
        int affectedListings,
        String notice
) {
}
