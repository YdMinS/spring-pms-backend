package com.pms.service.coupang;

/**
 * 쿠팡 주문(배송) 상태 — ordersheets 의 {@code status} 파라미터 / 응답 {@code deliveryStatus} 값과 동일.
 *
 * enum name() 이 그대로 쿠팡 코드값이다(예: {@code FINAL_DELIVERY}). 순서는 라이프사이클 순.
 * 동기화는 {@link CoupangOrderSyncServiceImpl} 에서 이 enum 전체를 상태별로 조회한다.
 */
public enum CoupangOrderStatus {
    ACCEPT,          // 결제완료 (사입 대상)
    INSTRUCT,        // 상품준비중
    DEPARTURE,       // 배송지시
    DELIVERING,      // 배송중
    FINAL_DELIVERY,  // 배송완료
    NONE_TRACKING;   // 업체 직접배송 (배송 연동 미적용, 추적불가)

    /**
     * 종결 상태 — 여기서 되돌아오지 않는다(상태 단조성).
     *
     * 이 부류는 한 번 기록되면 값이 다시 바뀌지 않으므로, 주문 생성일 기준 14일 창을 매번 다시 읽을
     * 이유가 없다(PLAN 2609_14 D1·D3). 활성 부류(ACCEPT·INSTRUCT)는 "아직 안 보낸 주문"이라
     * 오래 머물 수 있어 넓은 창이 필요하다.
     *
     * ⚠️ DEPARTURE·DELIVERING 은 엄밀히는 진행 중이지만, <b>우리 워크플로 기준</b>으로는 종결이다 —
     *    발송이 끝나 더 이상 작업 대상이 아니다. 발송처리 스킵 판정(ShipmentConfirm SKIP_STATUSES)이
     *    이 넷을 같은 부류로 묶는 것과 같은 기준이다.
     */
    public boolean isTerminal() {
        return this == DEPARTURE || this == DELIVERING || this == FINAL_DELIVERY || this == NONE_TRACKING;
    }
}
