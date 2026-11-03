package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * Sales of one seller (FEATURE_2609_30 / PLAN D3 · D4 · 03 ①).
 *
 * <p>🔴 Every amount here is on the <b>sale-date</b> axis of the period — {@code estSettlement} too (FEATURE_2611_05
 * D2 · D6). The money still to be received regardless of the period (the scheduled payouts) is no longer sent
 * (FEATURE_2611_05 D9 · D13).
 *
 * <p>🔴 The seller total is given on the sale-date axis only (D4-1). Cash basis (paid in) has a different cycle per
 * channel and loses its meaning when summed per seller — it is only in {@link ChannelSalesResponse#paidAmount()}.
 *
 * @param discount        🔴 <b>not taken out of the sales</b>. {@code discountAmount} mixes who funds it, and the
 *                        settlement API is what settles that (the {@code OrderLine} amount field comments). Sales and
 *                        discount go down separately
 * @param holdQty         refund-pending quantity. <b>Not</b> taken out of the live quantity — the sales would swing
 *                        every time one is confirmed (D14)
 * @param estNetProfit    {@code null} when any line has no cost snapshot. 🔴 Never filled with {@code Product.price}
 *                        — past profit would move every time a cost is edited
 * @param costBasisReady  same meaning as {@code estNetProfit != null}. The screen shows {@code —} on it
 * @param estSettlement   estimated settlement of the period's sales (FEATURE_2611_05 D12): sales − (discount − platform
 *                        discount) − estimated fee − {@code fixedCost}. Cost, delivery and box are not taken out.
 *                        🔴 {@code null} when any line has no commission base (D7) — never 0
 * @param fixedCost       fixed cost of the period — 🔴 the <b>sum of the seller's channels</b> (FEATURE_2609_33 / PLAN
 *                        2609_33 D12). The threshold is not judged again per seller: the platform charges per account,
 *                        so judging the sum would cross a two-channel seller's threshold earlier than real. Taken out of
 *                        {@code estNetProfit} only when that is not null (D6), and out of {@code estSettlement} the
 *                        same way
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
        BigDecimal estSettlement,
        BigDecimal fixedCost) {
}
