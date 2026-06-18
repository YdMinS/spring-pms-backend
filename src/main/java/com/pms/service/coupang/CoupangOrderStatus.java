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
    NONE_TRACKING    // 업체 직접배송 (배송 연동 미적용, 추적불가)
}
