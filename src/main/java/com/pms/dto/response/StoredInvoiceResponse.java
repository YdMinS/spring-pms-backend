package com.pms.dto.response;

import com.pms.domain.OrderShipment;
import com.pms.domain.ReservedShipmentItem;

import java.util.List;

/**
 * 내부 단계 배송 묶음 1개의 현재 송장 (E12 · E14, FEATURE_2609_75 / D18).
 *
 * @param internalStage INTERNAL_PREPARING · AWAITING_SHIPMENT
 * @param carrierCode   저장된 택배사 코드. 송장이 없으면 null
 * @param invoiceNumber 대표 송장(쉼표 목록의 첫 번째). 송장이 없으면 null
 * ⚠️ shipment 는 내부 단계가 있는 묶음만 넘긴다(호출자가 D29 로 거른다). live = 현재 송장 행(없으면 null).
 */
public record StoredInvoiceResponse(
        Long orderShipmentId,
        String externalShipmentId,
        String internalStage,
        String carrierCode,
        String invoiceNumber) {

    public static StoredInvoiceResponse of(OrderShipment shipment, ReservedShipmentItem live) {
        List<String> invoices = live == null ? List.of() : live.invoiceNumberList();
        return new StoredInvoiceResponse(shipment.getId(), shipment.getExternalShipmentId(),
                shipment.getInternalStage().name(),
                live == null ? null : live.getCarrierCode(),
                invoices.isEmpty() ? null : invoices.get(0));
    }
}
