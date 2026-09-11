package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <b>판매월 × 지급일</b> 한 줄 — "그 달 판매가 언제 얼마로 정산됐나" (FEATURE_2609_34).
 *
 * <p>🔴 정산 건 기준 화면과 <b>축이 반대</b>다. 저쪽은 "이 정산은 어떤 판매였나"(정산 → 판매)이고,
 * 이쪽은 "이 달 판매는 언제 정산됐나"(판매 → 정산)다. 한 달 판매가 여러 번에 나눠 정산되면
 * (주정산 채널·추가정산) 이 화면에서만 그 갈라짐이 보인다.
 *
 * @param saleMonth       판매월 {@code yyyy-MM}
 * @param settlementDate  그 몫이 지급된(또는 지급될) 날
 * @param paid            지급 완료 여부. 같은 달이라도 일부만 지급됐을 수 있다
 * @param orders          판매 건수
 * @param saleAmount      판매액 합
 * @param settlementAmount 정산액 합 — 🔴 환불 건은 음수로 반영된다
 */
public record SaleMonthSettlement(
        String saleMonth,
        LocalDate settlementDate,
        boolean paid,
        long orders,
        BigDecimal saleAmount,
        BigDecimal settlementAmount) {
}
