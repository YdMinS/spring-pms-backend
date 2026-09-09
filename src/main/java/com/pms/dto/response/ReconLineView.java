package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대사 리포트의 라인 1행 (FEATURE_2609_30 / 02 · PLAN D12 · D13).
 *
 * <p>🔴 <b>문의용 식별자를 반드시 싣는다</b>: {@code externalOrderId} · {@code platformOptionId} ·
 * {@code productName} · 인식일 · 지급일 · 정산유형. 사용자는 이 값을 복사해 WING·고객센터에 문의한다 —
 * 빠지면 "왜 적나"를 확인할 방법이 사라진다.
 *
 * @param diff      예상 − 실정산. 미분류 라인은 추정 자체가 불가능해 null 이다
 * @param unmatched 붙일 주문 라인이 없는 상태. <b>정상 상태</b>이며 합계에는 포함된다(PLAN D7)
 */
public record ReconLineView(
        String externalOrderId,
        String platformOptionId,
        String productName,
        LocalDate recognitionDate,
        LocalDate settlementDate,
        String settlementType,
        BigDecimal saleAmount,
        BigDecimal serviceFee,
        BigDecimal serviceFeeRatio,
        BigDecimal settlementAmount,
        BigDecimal diff,
        String label,
        boolean unmatched) {
}
