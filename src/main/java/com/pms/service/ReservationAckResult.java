package com.pms.service;

import java.util.List;

/** 예약 실행 ② 결과 — 성공 박스 id(쿠팡 shipmentBoxId) · 실패 박스(쿠팡 원문). */
public record ReservationAckResult(List<String> succeededBoxIds, List<ShipmentConfirmResult.FailedBox> failed) {
}
