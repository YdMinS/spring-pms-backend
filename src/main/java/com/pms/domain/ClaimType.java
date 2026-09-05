package com.pms.domain;

/**
 * 클레임 종류 (FEATURE_2609_18 / PLAN D1).
 *
 * 반품·교환은 공통 필드가 대부분이라 단일 {@code order_claim} 테이블에 이 구분자로 함께 저장한다.
 * 실제로 다른 것은 처리 액션뿐이며, 그 액션은 이 단계 범위 밖이다(D4).
 */
public enum ClaimType {
    RETURN,
    EXCHANGE
}
