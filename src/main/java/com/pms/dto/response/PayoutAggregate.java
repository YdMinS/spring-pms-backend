package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 채널(계정) 1건의 지급 묶음 집계 (FEATURE_2609_30 / PLAN D4 · D5-4 · D5-5).
 *
 * <p>🔴 {@code pendingPayout}(받을 돈)에는 <b>기간 필터가 없다</b>(D4). 지급 묶음의 축은 매출인식일이라
 * 판매일 기간과 겹치지 않는다 — "9월 매출 500만 / 9월 정산예정 180만" 같은 오해를 만들지 않으려면
 * 애초에 기간을 걸지 않고 <b>"받을 돈"</b> 이라고 부르는 편이 정확하다.
 *
 * <p>⚠️ {@code recon_status = AMOUNT_ONLY}(추가정산·유보금)도 {@code pendingPayout} 에 포함한다 —
 * 라인이 없을 뿐 실제로 받을 돈이다(D5-4·D5-5).
 *
 * @param paidAmount 기간 내 <b>지급 확정</b>액({@code status = PAID}, {@code finalSettlementDate} 기준).
 *                   현금주의는 채널 레벨에서만 낸다 — 채널마다 정산 주기가 달라 판매자 합산은 의미가 없다(D4-1)
 */
public record PayoutAggregate(
        Long accountId,
        BigDecimal pendingPayout,
        BigDecimal paidAmount,
        long unreconciledPayouts,
        long amountOnlyPayouts) {

    public static PayoutAggregate empty(Long accountId) {
        return new PayoutAggregate(accountId, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);
    }
}
