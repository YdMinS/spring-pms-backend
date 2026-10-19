package com.pms.dto.response;

import com.pms.domain.Platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sales of one channel (account) (FEATURE_2609_30 / PLAN D3 · D4-1 · 03 ②).
 *
 * <p>Settlement arrives per {@code vendorId} (= account), so <b>the smallest unit that can have a detail is the
 * channel</b>. That is why cash basis ({@code paidAmount}) is given here only — lifted to the seller level, channels
 * with different cycles mix and the sum loses its meaning (D4-1).
 *
 * @param cancelQty              confirmed cancel quantity. Already out of the sales — the value answers "how much was
 *                               cancelled"
 * @param refundedAmount         amount <b>taken out of the sales</b> by confirmed cancels ("refunded")
 * @param pendingRefundAmount    amount still in the sales that may come out ("refund pending").
 *                               🔴 capped by the effective quantity — a share already cancelled is not counted again
 * @param pendingPayout          "money to receive", independent of the period (D4)
 * @param paidAmount             paid in the period ({@code status = PAID}), cut by the confirmed payout date when
 *                               present, else {@code settlementDate} (FEATURE_2610_20 / D38 — the 11st 「지급 완료」
 *                               date). 🔴 Coupang's payment history never sends {@code finalSettlementDate} (always
 *                               NULL), so Coupang rows are cut by {@code settlementDate}
 * @param lastSettlementSyncAt   when the settlement ledger was last loaded. null = a channel never read yet
 * @param fixedCost              fixed cost of the period (FEATURE_2609_33 / PLAN 2609_33 D6). 🔴 Sent as <b>its own
 *                               field</b> — folded into {@code estNetProfit}, it would vanish on channels with
 *                               {@code costBasisReady = false} and the screen would read "fixed cost 0". It is taken
 *                               out of {@code estNetProfit} only when that is not null
 * @param fixedCostMonths        number of <b>months</b> a fixed cost was actually charged (not the number of items).
 *                               The screen uses it to show the basis (D11-2). 🔴 No pro-rating — even a one-day period
 *                               carries the whole month (D4)
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
