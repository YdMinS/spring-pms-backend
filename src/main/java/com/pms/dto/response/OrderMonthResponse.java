package com.pms.dto.response;

/**
 * 주문이 존재하는 달과 건수 (GET /api/orders/months).
 *
 * 용도 = 기간 드롭다운에서 데이터 없는 달을 "(데이터 없음)" 으로 라벨링하는 것(FEATURE_2609_08 D18).
 * ⚠️ 판매자 필터와 무관한 **전체 기준**이다(D17) — 목적이 "어느 달에 데이터가 있나"이기 때문.
 *
 * @param ym    "yyyy-MM" (월은 0-패딩)
 * @param count 그 달의 주문 라인 수
 */
public record OrderMonthResponse(String ym, long count) { }
