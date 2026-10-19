package com.pms.dto.response;

/**
 * One 11st server-fee link waiting for the person's first-month answer (FEATURE_2610_20 / D45 ⑦).
 *
 * <p>The 「정산 내역」 screen shows one yellow row per item, with [예] [아니오]:
 * 「{channel name} 11번가 채널: 지난달({askMonth as YYYY년 M월}) 11번가 구매확정액이 500만 원 이상이었나요?」
 *
 * @param linkId     the fixed-cost link id — the answer goes to {@code POST /first-month-questions/{linkId}}
 * @param firstMonth M0, {@code YYYY-MM}
 * @param askMonth   the month asked about = M0's previous month, {@code YYYY-MM}
 */
public record FirstMonthQuestionResponse(
        Long linkId,
        Long accountId,
        String accountAlias,
        String sellerName,
        String firstMonth,
        String askMonth) {
}
