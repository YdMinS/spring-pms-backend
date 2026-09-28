package com.pms.service.reservation;

import java.util.Collection;
import java.util.List;

/**
 * 배송 묶음의 내부 단계(「내부 상품준비중」·「발송대기중」) 전환 창구 (FEATURE_2609_75 / D1 · D14 · D18).
 *
 * ⚠️ 쿠팡에 아무것도 보내지 않는다 — DB 만 바꾼다.
 */
public interface InternalShipmentStageService {

    /** 선택 라인이 속한 결제완료 쿠팡 배송 묶음을 「내부 상품준비중」으로(E1). */
    InternalStageResult markInternal(List<Long> orderItemIds);

    /** 「내부 상품준비중」 배송 묶음을 없음으로 되돌린다(E2, D18 행1). */
    InternalStageResult releaseInternal(List<Long> orderItemIds);

    /** 이 배송 묶음 중 실행 중인 예약에 든 것이 있으면 400 (D18 「처리 중」). 빈 입력은 통과. */
    void assertNotRunning(Collection<Long> orderShipmentIds);

    /** 수동 [발주처리]가 성공한 배송 묶음 — 내부 단계를 지우고 남은 예약 결과·보관 송장(STORED)을 해제한다(D14). */
    void clearAfterManualAcknowledge(Collection<Long> orderShipmentIds);

    /**
     * [지금 발송]으로 송장 등록까지 성공한 배송 묶음 — 내부 단계를 지우고 남은 예약 결과·보관 송장(STORED)을 해제한다(D27 · D18).
     * 호출자는 02 의 {@code ShipmentConfirmServiceImpl.shipInternalOrders} 하나다(파일 [지금 발송]·저장된 송장 [지금 발송] 둘 다 거기를 지난다).
     */
    void clearAfterShipNow(Collection<Long> orderShipmentIds);

    /**
     * [지금 발송]이 일부 수량 취소로 뺀 배송 묶음 (FEATURE_2609_75 / D27 × D17) — 남은 예약 결과(PENDING·FAILED)를 RELEASED 로 닫고
     * 「내부 상품준비중」 으로 되돌린다. 보관 송장(STORED)도 RELEASED 로 닫는다(D18 — 일부 수량 취소는 송장을 버린다). 호출자는 02 의 {@code ShipmentConfirmServiceImpl.shipInternalOrders} 하나다.
     */
    void releasePartialCancel(Collection<Long> orderShipmentIds);
}
