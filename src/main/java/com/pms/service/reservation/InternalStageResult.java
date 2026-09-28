package com.pms.service.reservation;

import java.util.List;

/**
 * 내부 발주 · 해제 · 예약 취소 결과 (FEATURE_2609_75 / PLAN §4-3).
 *
 * @param requestedLines   조회에 성공한 라인 수(dedupe 후)
 * @param changedShipments 단계를 바꾼 배송 묶음 수
 * @param skippedOrderIds  대상이 아니라 건너뛴 주문번호(바뀐 묶음이 하나도 없는 주문만)
 * @param unsupported      비-쿠팡이거나 배송 묶음이 없어 처리할 수 없는 주문번호
 */
public record InternalStageResult(
        int requestedLines,
        int changedShipments,
        List<String> skippedOrderIds,
        List<String> unsupported) {
}
