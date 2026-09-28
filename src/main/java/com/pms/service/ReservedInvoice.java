package com.pms.service;

import java.util.List;

/**
 * 예약 실행 ③ 송장 등록 1건 (FEATURE_2609_75 / D27). 결과 파일이 아니라 예약 결과 행에서 온다.
 *
 * @param invoiceNumbers 첫 번째가 쿠팡에 올리는 대표 송장(2609_40 D8). 전부 {@code shipment_parcel} 후보
 */
public record ReservedInvoice(Long orderShipmentId, String externalOrderId, String carrierCode,
                              List<String> invoiceNumbers) {
}
