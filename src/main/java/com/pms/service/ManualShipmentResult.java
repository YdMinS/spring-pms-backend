package com.pms.service;

import java.util.List;

/**
 * 단건 발송처리 결과.
 *
 * @param orderId        쿠팡 주문번호
 * @param shipmentBoxId  전송한 박스(운송장이 붙는 단위, PLAN 2609_11 D1)
 * @param mode           "CREATE"(신규 업로드) | "UPDATE"(송장수정) — 서버가 상태로 결정(D3)
 * @param sentLines      전송한 dto 수 = 박스의 옵션 라인 수
 * @param succeeded      쿠팡 responseList 의 succeed=true 건수
 * @param failed         실패 상세(쿠팡 원문, D6) — {@link ShipmentConfirmResult.FailedBox} 재사용
 * @param resultStatus   성공 후 로컬 상태(CREATE 성공 시 "DEPARTURE", 그 외 null) — 클라이언트가 화면 갱신에 쓴다(D4)
 */
public record ManualShipmentResult(String orderId, String shipmentBoxId, String mode, int sentLines,
                                   int succeeded, List<ShipmentConfirmResult.FailedBox> failed,
                                   String resultStatus) {
}
