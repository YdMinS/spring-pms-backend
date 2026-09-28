package com.pms.domain;

/**
 * 배송 묶음의 내부 진행 단계 (FEATURE_2609_75 / D1 · D9 · D28). null = 없음.
 * 🔴 쿠팡 상태({@link OrderStatus})가 아니다 — 응답 {@code status} 와 별개 필드({@code internalStage})로 나간다.
 */
public enum InternalShipmentStage {
    /** 「내부 상품준비중」 — 오클릭스 안에서만 발주한 상태(D10). */
    INTERNAL_PREPARING,
    /** 「발송대기중」 — 송장을 받아 예약 시각을 기다리는 상태(D11). */
    AWAITING_SHIPMENT
}
