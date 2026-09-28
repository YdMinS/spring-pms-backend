package com.pms.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [예약 발송] 결과 (E5, PLAN §4-3).
 *
 * @param reservationId     새 예약 id. 새로 예약한 묶음이 없으면(송장 교체만/전부 제외) null
 * @param reservedShipments 새로 예약한 배송 묶음 수
 * @param updatedInvoices   송장만 바꾼 결과 행 수(D18 송장 수정)
 * @param excluded          예약하지 않은 주문과 사유
 */
public record ReservationCreateResult(
        Long reservationId,
        LocalDateTime executeAt,
        int reservedShipments,
        int updatedInvoices,
        List<ExcludedOrder> excluded) {

    public record ExcludedOrder(String orderId, String reason) {
    }
}
