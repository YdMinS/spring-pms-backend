package com.pms.service;

import java.util.List;
import java.util.Map;

/** 예약 실행 ③ 결과 — 성공한 배송 묶음 id · 실패 사유(배송 묶음 id → 문구). 둘 다 없는 id = 응답에 없었다. */
public record ReservedInvoiceResult(List<Long> succeededShipmentIds, Map<Long, String> failedReasons) {
}
