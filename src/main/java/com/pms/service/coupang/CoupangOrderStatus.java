package com.pms.service.coupang;

import com.pms.domain.OrderStatus;

import java.util.Arrays;
import java.util.List;

/**
 * 쿠팡 주문(배송) 상태 코드 — ordersheets 의 {@code status} <b>쿼리 파라미터</b> 값.
 *
 * <p>enum name() 이 그대로 쿠팡 코드값이다(예: {@code FINAL_DELIVERY}). 순서는 라이프사이클 순.
 *
 * <p>🔴 역할이 <b>"쿠팡 조회 파라미터"로 좁혀졌다</b>(FEATURE_2609_26 / PLAN D4). 우리 상태는
 * {@link OrderStatus} 하나이며, 화면·필터·전송 판정은 전부 그쪽을 본다. 이 enum 은
 * {@code status=ACCEPT} 같은 <b>쿼리 문자열을 만들 때만</b> 쓴다 — 그래서 지우지 않는다.
 *
 * <p>❌ 여기에 {@code isTerminal()} 같은 <b>판정</b>을 다시 두지 말 것 — 판정의 소유자는
 * {@link OrderStatus#isTerminal()} 하나다(2609_16 D2: 상태 목록 복제 금지).
 */
public enum CoupangOrderStatus {
    ACCEPT,          // 결제완료 (사입 대상)
    INSTRUCT,        // 상품준비중
    DEPARTURE,       // 배송지시
    DELIVERING,      // 배송중
    FINAL_DELIVERY,  // 배송완료
    NONE_TRACKING;   // 업체 직접배송 (배송 연동 미적용, 추적불가)

    /**
     * 이 쿠팡 코드의 중립 상태.
     *
     * <p>6코드 전부 매핑이 있으므로 실패하지 않는다 — 매핑이 비면 {@link OrderStatus#fromCoupang}
     * 와 이 enum 이 갈라진 것이라 조용히 넘기지 않고 던진다.
     */
    public OrderStatus toOrderStatus() {
        return OrderStatus.fromCoupang(name())
                .orElseThrow(() -> new IllegalStateException(
                        "쿠팡 상태 매핑 누락: " + name() + " — OrderStatus.fromCoupang 을 확인할 것"));
    }

    /**
     * 중립 상태 → 조회에 쓸 쿠팡 코드 목록.
     *
     * <p>🔴 1:N 이다 — {@code SHIPPED} 하나가 {@code DEPARTURE}·{@code NONE_TRACKING} 둘로 펼쳐진다
     * (PLAN D5). 목록을 여기에 손으로 적지 않고 {@link #toOrderStatus()} 의 역상으로 만든다.
     */
    public static List<CoupangOrderStatus> forOrderStatus(OrderStatus status) {
        return Arrays.stream(values())
                .filter(s -> s.toOrderStatus() == status)
                .toList();
    }
}
