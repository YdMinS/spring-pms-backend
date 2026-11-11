package com.pms.dto.response;

import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 발송 현황 1행 = 주문(배송 묶음) 1개 (E6·E8·E9·E13, FEATURE_2609_75 / D30 · PLAN §4-3).
 *
 * @param id             result row id — the path variable of the row actions (E8 · E9)
 * @param orderShipmentId delivery bundle id — the path variable of [송장 수정] (E12, D18)
 * @param orderItemIds   order line ids of that delivery bundle — the [예약 취소] (E7) input
 * @param executeAt      scheduled run time (KST)
 * @param lastRunAt      last run time of <b>this row (order)</b> (KST, D28 · D30) = {@code reserved_shipment_item.last_run_at}; null = this row has not run yet
 * @param status         status of the reservation the row belongs to (SCHEDULED · RUNNING · DONE · STOPPED · CANCELLED)
 * @param firstRunKind   ON_TIME · DELAYED · null. DELAYED = 「지연 실행」 (D15). MANUAL is never stored ([다시 시도] is not a first run)
 * @param invoiceNumber  representative invoice (the first of the comma list); null when the list is empty
 * @param result         PENDING · SUCCEEDED · FAILED · CANCELLED · EXTERNAL · RELEASED
 * @param platform       marketplace {@code Platform} name of the row's delivery bundle (e.g. COUPANG · ELEVENST,
 *                       FEATURE_2611_23 / D64 ①); null when the bundle has no line
 * ⚠️ item 의 orderShipment·reservedShipment 는 호출자가 로딩해 넘긴다(리포지토리 {@code @EntityGraph}).
 */
public record ReservedShipmentRowResponse(
        Long id,
        Long orderShipmentId,
        String externalOrderId,
        String externalShipmentId,
        List<Long> orderItemIds,
        LocalDateTime executeAt,
        LocalDateTime lastRunAt,
        String status,
        String firstRunKind,
        String carrierCode,
        String invoiceNumber,
        String result,
        String failureReason,
        String platform) {

    public static ReservedShipmentRowResponse of(ReservedShipmentItem item, List<Long> orderItemIds, String platform) {
        ReservedShipment reservation = item.getReservedShipment();
        List<String> invoices = item.invoiceNumberList();
        return new ReservedShipmentRowResponse(item.getId(), item.getOrderShipment().getId(),
                item.getExternalOrderId(), item.getOrderShipment().getExternalShipmentId(), orderItemIds,
                reservation.getExecuteAt(), item.getLastRunAt(), reservation.getStatus().name(),
                reservation.getFirstRunKind() == null ? null : reservation.getFirstRunKind().name(),
                item.getCarrierCode(), invoices.isEmpty() ? null : invoices.get(0),
                item.getResult().name(), item.getFailureReason(), platform);
    }
}
