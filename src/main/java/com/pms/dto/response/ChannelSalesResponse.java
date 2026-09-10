package com.pms.dto.response;

import com.pms.domain.Platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 채널(계정)별 매출 한 줄 (FEATURE_2609_30 / PLAN D3 · D4-1 · 03 ②).
 *
 * <p>정산이 {@code vendorId}(= 계정) 단위로 오므로 <b>상세가 존재할 수 있는 최소 단위가 채널</b>이다.
 * 그래서 현금주의({@code paidAmount})는 여기서만 제공한다 — 판매자 레벨로 올리면 주기가 다른 채널이
 * 섞여 합계의 뜻이 사라진다(D4-1).
 *
 * @param pendingPayout          기간 무관 "받을 돈"(D4)
 * @param paidAmount             기간 내 지급 확정({@code status = PAID}, {@code finalSettlementDate} 기준)
 * @param lastSettlementSyncAt   정산 원장을 마지막으로 적재한 시각. null 이면 아직 한 번도 안 읽은 채널이다
 * @param amountOnlyPayouts      라인 없이 금액만 있는 묶음 수(추가정산·유보금) — 0 이 아닌 것이 정상이다(D5-5)
 * @param payoutCount            전체 지급 묶음 수. 🔴 0 = 정산 이력 없음 — 화면이 "금액 일치"와 구분해서
 *                               표시한다. 이 필드가 없으면 정산 전 채널이 "전부 맞음"으로 보인다
 * @param fixedCost              기간 고정비(FEATURE_2609_33 / PLAN 2609_33 D6). 🔴 <b>자기 필드로</b> 내려간다 —
 *                               {@code estNetProfit} 에 녹이면 {@code costBasisReady = false} 인 채널에서
 *                               고정비가 통째로 사라져 화면이 "고정비 0" 으로 읽는다. {@code estNetProfit} 에서는
 *                               null 이 아닐 때만 빠져 있다
 * @param fixedCostMonths        고정비가 실제로 부과된 <b>달 수</b>(항목 수가 아니다). 화면이 판정 근거를
 *                               보여주는 데 쓴다(D11-2). 🔴 일할 계산은 없다 — 하루만 조회해도 그 달 전액이다(D4)
 */
public record ChannelSalesResponse(
        Long accountId,
        String accountAlias,
        Platform platform,
        Long sellerId,
        BigDecimal grossSales,
        BigDecimal discount,
        long netQty,
        long holdQty,
        BigDecimal estFee,
        BigDecimal estNetProfit,
        boolean costBasisReady,
        BigDecimal pendingPayout,
        BigDecimal paidAmount,
        LocalDateTime lastSettlementSyncAt,
        long unreconciledPayouts,
        long amountOnlyPayouts,
        long payoutCount,
        BigDecimal fixedCost,
        int fixedCostMonths) {
}
