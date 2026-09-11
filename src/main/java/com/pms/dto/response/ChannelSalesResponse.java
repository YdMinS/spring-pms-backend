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
 * @param cancelQty              취소 확정 수량. 매출에서는 이미 빠져 있다 — 화면이 "얼마나 취소됐나"에
 *                               답하는 값이다
 * @param refundedAmount         취소 확정으로 <b>매출에서 빠진</b> 금액("환불완료 금액")
 * @param pendingRefundAmount    아직 매출에 남아 있지만 빠질 수 있는 금액("환불대기 금액").
 *                               🔴 유효수량 상한이 걸려 있다 — 이미 취소 확정된 몫을 다시 세지 않는다
 * @param pendingPayout          기간 무관 "받을 돈"(D4)
 * @param paidAmount             기간 내 지급 확정({@code status = PAID}, {@code settlementDate} 기준).
 *                               🔴 {@code finalSettlementDate} 는 지급내역 API 가 주지 않는다(항상 NULL)
 * @param lastSettlementSyncAt   정산 원장을 마지막으로 적재한 시각. null 이면 아직 한 번도 안 읽은 채널이다
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
        String sellerName,
        BigDecimal grossSales,
        BigDecimal discount,
        long netQty,
        long holdQty,
        BigDecimal estFee,
        BigDecimal estNetProfit,
        boolean costBasisReady,
        long cancelQty,
        BigDecimal refundedAmount,
        BigDecimal pendingRefundAmount,
        BigDecimal pendingPayout,
        BigDecimal paidAmount,
        LocalDateTime lastSettlementSyncAt,
        BigDecimal fixedCost,
        int fixedCostMonths) {
}
