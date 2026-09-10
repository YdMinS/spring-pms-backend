package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 인식월 단위 참고 대조 (FEATURE_2609_32 / PLAN 2609_32 D4·D4-1·D6·D8).
 *
 * <p>🔴 지급 건 단위 검증식({@code ReconReportResponse.BlockA})이 <b>아니다</b>. 추가정산·유보금은 라인을
 * 붙이지 않아 건 단위로는 영원히 −전액이 나온다 — 그 달 전체로 보면 추가정산이 차이를 <b>메우는</b> 항목이다.
 *
 * <p>🔴 {@code ourLineCount == 0} 은 "차이가 전액"이 아니라 <b>그 달 매출내역을 아직 안 불러왔다</b>는
 * 뜻이다(D4-1). 매출 라인({@code sync/period})과 지급 건({@code payout/sync})은 적재 경로가 완전히
 * 별개다 — 화면은 이때 {@code diff} 를 그리지 말고 적재를 유도해야 한다.
 *
 * <p>⚠️ 라인이 있어도 {@code diff} 가 0이 아닐 수 있다. 우리 축은 라인의 {@code recognitionDate}(달력 월),
 * 쿠팡 축은 지급 건의 {@code revenueRecognitionMonth} 로 완전히 같지 않다 — 참고 지표다(D8).
 *
 * @param revenueRecognitionMonth 인식월 {@code yyyy-MM}
 * @param ourLineTotal       그 계정·그 달의 라인 합 (REFUND 는 음수)
 * @param ourLineCount       그 계정·그 달의 라인 <b>건수</b>. 🔴 0 = 미적재 (D4-1)
 * @param payoutTotal        그 계정·그 달의 <b>모든 유형</b> 지급 건 finalAmount 합. 미수신 건은 뺀다(D6)
 * @param diff               {@code ourLineTotal − payoutTotal}. 양수 = 우리가 더 크게 봤다
 * @param payoutCount        그 달 지급 건 수(전체)
 * @param pendingPayoutCount finalAmount 미수신 건 수. 0이 아니면 화면이 "미수신 n건"을 밝혀야 한다
 */
public record MonthCheck(String revenueRecognitionMonth,
                         BigDecimal ourLineTotal,
                         long ourLineCount,
                         BigDecimal payoutTotal,
                         BigDecimal diff,
                         long payoutCount,
                         long pendingPayoutCount) {
}
