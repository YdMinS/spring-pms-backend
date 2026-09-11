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
 * <p>🔴 <b>대사 상태 집계(건수)를 여기에 다시 넣지 말 것</b>(FEATURE_2609_34). 지급 묶음은 매출인식일 축이라
 * 판매일 기간으로 좁혀지지 않는다 — 기간을 바꿔도 안 변하는 건수를 기간 필터가 달린 행에 배지로 걸면
 * "이번 달에 13건이 어긋났다"로 읽힌다(실제로는 그 채널의 <b>전체</b> 건수였다). 대사 상태는 인식월별
 * 정산 목록({@code /payouts/by-recognition})이 건별로 보여준다.
 *
 * @param paidAmount 기간 내 <b>지급 확정</b>액({@code status = PAID}, {@code settlementDate} 기준).
 *                   🔴 {@code finalSettlementDate} 는 지급내역 API 응답에 없어 항상 NULL 이다 — 기준일로 쓰면 0원이 된다.
 *                   현금주의는 채널 레벨에서만 낸다 — 채널마다 정산 주기가 달라 판매자 합산은 의미가 없다(D4-1)
 */
public record PayoutAggregate(
        Long accountId,
        BigDecimal pendingPayout,
        BigDecimal paidAmount) {

    /** 집계 쿼리가 행을 내주지 않은 채널 = 지급 묶음이 하나도 없다. */
    public static PayoutAggregate empty(Long accountId) {
        return new PayoutAggregate(accountId, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
