package com.pms.service.reservation;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;

import java.util.List;

/**
 * 로컬 DB 로 본 배송 묶음 상태 (FEATURE_2609_75 / D16 · D17 · D18 · D27). 쿠팡을 부르지 않는다 —
 * 주문 동기화가 로컬 DB 를 최신으로 유지한다.
 *
 * <p>🔴 판정 규칙의 유일한 소유자({@link #judge}). 예약 실행기({@code ReservedShipmentExecutor} — ① 뒤 · ② 직전 · ③ 직전)와
 * [지금 발송]({@code ShipmentConfirmServiceImpl.shipInternalOrders} — ② 직전 · ③ 직전)이 같이 쓴다. 다른 곳에서 다시 만들지 않는다.
 */
public enum ShipmentLocalState {
    /** 전량 취소 → 「취소됨」(D17). */
    CANCELLED,
    /** 발송 이후 → 「외부에서 처리됨」(D18 7행). */
    EXTERNAL,
    /** 일부 수량 취소 → 예약 해제(RELEASED) + 「내부 상품준비중」 복귀, 사용자가 직접 처리(D17 · D16 🔁 · D27). */
    PARTIALLY_CANCELLED,
    /** 이미 상품준비중 → ② 건너뛰고 ③(D18 6행). */
    PREPARING,
    /** 결제완료 → ②. */
    PAID,
    /** 판정할 수 없다 → 실패. */
    UNKNOWN;

    /**
     * 판정 순서 = 전량 취소 → 발송 이후 → 일부 수량 취소 → 상품준비중 → 결제완료. 취소는 <b>취소 수량만</b> 본다(D17 🔁 —
     * 보류 수량을 더하는 {@code OrderLine} 의 전량 취소 메서드는 쓰지 않는다). 상태는 첫 라인의 저장 상태.
     */
    public static ShipmentLocalState judge(List<OrderLine> lines) {
        if (lines.stream().allMatch(l -> l.getOrderQty() != null && l.getOrderQty() > 0
                && l.getCancelQty() >= l.getOrderQty())) {
            return CANCELLED;
        }
        OrderStatus status = lines.get(0).getStatus();
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERING || status == OrderStatus.DELIVERED) {
            return EXTERNAL;
        }
        if (lines.stream().anyMatch(l -> l.getCancelQty() > 0)) {
            return PARTIALLY_CANCELLED;
        }
        if (status == OrderStatus.PREPARING) {
            return PREPARING;
        }
        return status == OrderStatus.PAID ? PAID : UNKNOWN;
    }
}
