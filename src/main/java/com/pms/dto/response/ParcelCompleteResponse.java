package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

import java.math.BigDecimal;

/**
 * 박스 완료 결과 (FEATURE_2609_40 / PLAN D4 · D15).
 *
 * <p>🔴 절약액을 담지 않는다 — {@code expected − actual} 은 언제든 뺄 수 있고, 저장하거나 따로 내려주면
 * 세 값이 어긋날 자리가 생긴다(D4). 집계 화면(2609_41)도 이 세 칸에서 계산한다.
 *
 * <p>⚠️ 이미 완료된 박스에 다시 요청하면 <b>아무것도 만들지 않고</b> 저장돼 있던 이 값이 그대로 온다(D15).
 *
 * @param expectedBoxCost      판매가 계산에 들어간 상자비 합계. 설정을 못 읽으면 {@code null} 이다 —
 *                             집계가 비는 것이 출고를 막는 것보다 낫다
 * @param actualBoxCost        실제 쓴 상자의 원가(재활용 상자는 0)
 * @param expectedDeliveryCost 판매가 계산에 들어간 택배비 합계(2609_41 의 재료)
 */
public record ParcelCompleteResponse(Long parcelId, ParcelStatus status, int packedQty,
                                     BigDecimal expectedBoxCost, BigDecimal actualBoxCost,
                                     BigDecimal expectedDeliveryCost) {
}
