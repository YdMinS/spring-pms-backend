package com.pms.domain;

/**
 * 예약 결과 1행의 결과 (FEATURE_2609_75 / D16 · D17 · D18).
 * STORED = 예약 없이 송장만 보관한 행(예약 id NULL, D18) — 현황·주문별 기록 응답에 나오지 않는다.
 */
public enum ReservedItemResult {
    PENDING, SUCCEEDED, FAILED, CANCELLED, EXTERNAL, RELEASED, STORED
}
