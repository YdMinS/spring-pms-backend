package com.pms.domain;

import java.util.Optional;

/**
 * 플랫폼 중립 주문 라인 상태 (FEATURE_2609_26 / PLAN D4·D5).
 *
 * <p>화면·필터·전송 판정은 이 정규화 값으로만 동작해 플랫폼이 늘어도 바뀌지 않는다.
 * 원문 상태 코드는 정보 손실 없이 {@link CoupangOrderLine#getPlatformStatus()} 에 그대로 보존한다
 * ({@link OrderClaim}·{@link CustomerInquiry} 의 {@code platform_status} 와 같은 형태).
 *
 * <p>⚠️ {@code CANCELLED} 는 <b>저장되지 않는 파생값</b>이다 — 쿠팡이 주는 상태가 아니라
 * 취소·환불대기 수량으로 판정한다({@link OrderLine#effectiveStatus()}). 적재 시점에 굽지 않는다.
 */
public enum OrderStatus {
    PAID,        // 결제완료 — 사입 대상
    PREPARING,   // 상품준비중
    SHIPPED,     // 발송/배송지시 완료
    DELIVERING,  // 배송중
    DELIVERED,   // 배송완료
    CANCELLED;   // 전량 취소 (파생값 — DB 에 저장되지 않는다)

    /**
     * 종결 상태 — 우리 워크플로 기준으로 더 이상 작업 대상이 아니다.
     *
     * <p>2609_14 의 동기화 창 축소와 2609_07 의 발송처리 스킵이 이 판정을 쓴다.
     * 쿠팡 terminal 4코드(DEPARTURE·DELIVERING·FINAL_DELIVERY·NONE_TRACKING)가 매핑 후 전부 여기 든다.
     */
    public boolean isTerminal() {
        return this == SHIPPED || this == DELIVERING || this == DELIVERED || this == CANCELLED;
    }

    /**
     * 쿠팡 배송 상태 코드 → 중립 상태 (PLAN D5).
     *
     * <p>ACCEPT→PAID · INSTRUCT→PREPARING · DEPARTURE→SHIPPED · DELIVERING→DELIVERING ·
     * FINAL_DELIVERY→DELIVERED · NONE_TRACKING→SHIPPED(추적 가능 여부는 상태가 아니라
     * {@link OrderShipment#getTrackingAvailable()} 의 속성이다).
     *
     * <p>🔴 <b>모르는 값은 {@link Optional#empty()}</b> — 임의 기본값을 주지 않는다(PLAN D7).
     * {@link ClaimStatus#fromCoupangReturn} 의 "모르는 값 → RECEIVED" 를 복제하지 말 것:
     * 클레임 상태는 조회용이지만 주문 상태는 <b>전송 판정</b>에 쓰인다. 지금 발송처리 스킵 집합이
     * 블랙리스트라 모르는 상태의 기본 동작이 "전송"이고, 그건 오발송 방향이다.
     * 미적재는 눈에 보이고 오발송은 안 보인다.
     */
    public static Optional<OrderStatus> fromCoupang(String coupangStatus) {
        if (coupangStatus == null || coupangStatus.isBlank()) {
            return Optional.empty();
        }
        return switch (coupangStatus.trim().toUpperCase()) {
            case "ACCEPT" -> Optional.of(PAID);
            case "INSTRUCT" -> Optional.of(PREPARING);
            case "DEPARTURE", "NONE_TRACKING" -> Optional.of(SHIPPED);
            case "DELIVERING" -> Optional.of(DELIVERING);
            case "FINAL_DELIVERY" -> Optional.of(DELIVERED);
            default -> Optional.empty();
        };
    }
}
