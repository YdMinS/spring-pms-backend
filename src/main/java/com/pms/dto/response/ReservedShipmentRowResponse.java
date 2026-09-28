package com.pms.dto.response;

import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 발송 현황 1행 = 주문(배송 묶음) 1개 (E6·E8·E9·E13, FEATURE_2609_75 / D30 · PLAN §4-3).
 *
 * @param id             결과 행 id — 행 작업(E8·E9)의 경로 변수
 * @param orderShipmentId 배송 묶음 id — [송장 수정](E12)의 경로 변수(D18)
 * @param orderItemIds   그 배송 묶음의 주문 라인 id — [예약 취소](E7) 입력
 * @param executeAt      실행 예정 시각(KST)
 * @param lastRunAt      <b>이 행(주문)의</b> 마지막 실행 시각(KST, D28 · D30) = {@code reserved_shipment_item.last_run_at}. null = 이 행은 아직 실행 전
 * @param status         그 행이 속한 예약의 상태(SCHEDULED·RUNNING·DONE·STOPPED·CANCELLED)
 * @param firstRunKind   ON_TIME·DELAYED·null. DELAYED = 「지연 실행」(D15). MANUAL 은 저장되지 않는다([다시 시도]는 첫 실행이 아니다)
 * @param invoiceNumber  대표 송장(쉼표 목록의 첫 번째). 빈 목록이면 null
 * @param result         PENDING·SUCCEEDED·FAILED·CANCELLED·EXTERNAL·RELEASED
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
        String failureReason) {

    public static ReservedShipmentRowResponse of(ReservedShipmentItem item, List<Long> orderItemIds) {
        ReservedShipment reservation = item.getReservedShipment();
        List<String> invoices = item.invoiceNumberList();
        return new ReservedShipmentRowResponse(item.getId(), item.getOrderShipment().getId(),
                item.getExternalOrderId(), item.getOrderShipment().getExternalShipmentId(), orderItemIds,
                reservation.getExecuteAt(), item.getLastRunAt(), reservation.getStatus().name(),
                reservation.getFirstRunKind() == null ? null : reservation.getFirstRunKind().name(),
                item.getCarrierCode(), invoices.isEmpty() ? null : invoices.get(0),
                item.getResult().name(), item.getFailureReason());
    }
}
