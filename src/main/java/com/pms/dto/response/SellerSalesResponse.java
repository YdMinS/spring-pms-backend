package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 판매자별 매출 한 줄 (FEATURE_2609_30 / PLAN D3 · D4 · 03 ①).
 *
 * <p>🔴 <b>축이 둘 섞여 있다.</b> {@code grossSales}·{@code discount}·{@code estFee}·{@code estNetProfit} 은
 * <b>판매일(발생주의)</b> 축의 기간 합계이고, {@code pendingPayout} 은 <b>매출인식일</b> 축의 <b>기간 무관</b>
 * 총액이다. 셋을 같은 "9월" 라벨 아래 두면 <i>"매출 500만인데 정산예정 180만?"</i> 이 반드시 나온다 —
 * 대부분 구매확정 전이라 정상인데도. 화면은 {@code pendingPayout} 을 <b>"받을 돈"</b> 으로 표시한다.
 *
 * <p>🔴 판매자 합계는 <b>발생주의 축으로만</b> 낸다(D4-1). 현금주의(입금)는 채널마다 정산 주기가 달라
 * 판매자 단위로 더하면 의미를 잃는다 — {@link ChannelSalesResponse#paidAmount()} 에만 있다.
 *
 * @param discount        🔴 <b>매출에서 빼지 않은 값</b>이다. {@code discountAmount} 는 부담 주체가 섞여 있고
 *                        부담을 확정하는 것은 정산 API 다({@code OrderLine} 금액 필드 주석). 매출과 할인을
 *                        각각 내려보내고, 해석은 대사(02)가 한다
 * @param holdQty         환불대기 수량 합계. 유효수량에서 <b>빼지 않는다</b> — 확정될 때마다 매출이 출렁인다(D14)
 * @param estNetProfit    원가 스냅샷이 없는 라인이 하나라도 섞이면 {@code null} 이다. 🔴 {@code Product.price}
 *                        로 메우지 않는다 — 원가를 고칠 때마다 과거 순이익이 소급 변동한다
 * @param costBasisReady  {@code estNetProfit != null} 과 같은 뜻. 화면이 {@code —} 를 표시할 근거다
 * @param fixedCost       기간 고정비 — 🔴 그 판매자 <b>채널들의 합</b>이다(FEATURE_2609_33 / PLAN 2609_33 D12).
 *                        판매자 단위로 임계를 다시 판정하지 않는다: 플랫폼이 계정 단위로 청구하므로 합쳐서
 *                        판정하면 채널 2개짜리 판매자가 실제보다 일찍 임계를 넘는다.
 *                        {@code estNetProfit} 에서는 null 이 아닐 때만 빠져 있다(D6)
 */
public record SellerSalesResponse(
        Long sellerId,
        String sellerName,
        BigDecimal grossSales,
        BigDecimal discount,
        long netQty,
        long holdQty,
        BigDecimal estFee,
        BigDecimal estNetProfit,
        boolean costBasisReady,
        BigDecimal pendingPayout,
        long unreconciledPayouts,
        BigDecimal fixedCost) {
}
