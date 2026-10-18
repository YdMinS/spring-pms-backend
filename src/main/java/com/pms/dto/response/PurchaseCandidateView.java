package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A purchase whose goods have not (fully) been checked in yet (FEATURE_2609_28 / prompt 04 Step 6-1).
 *
 * <p>While the stock link is on, STOCK_IN + PURCHASE requires a {@code purchaseRecordId}, so without this
 * list a purchase check-in could not be created at all (off = no record needed, FEATURE_2610_09 D71 round 12).
 *
 * <p>🔴 No {@code externalOrderId} any more (PLAN 2609_29 D3·D20): a purchase record no longer knows
 * which order it belonged to, so the value cannot be derived. {@code sellerName} replaces it as the
 * row's identity — every purchase now belongs to exactly one seller.
 *
 * <p>⚠️ While the stock link setting is on, every check-in happens with the purchase, so this list is
 * <b>legitimately empty</b> apart from {@code recordStock=false} purchases. Purchases saved while the setting
 * was off never show here (FEATURE_2610_09 D71 ④ · D84).
 *
 * <p>⚠️ {@code unitPrice} may be null ("amount unknown") — the row stays in the list, because the
 * goods can arrive before the receipt is known.
 */
public record PurchaseCandidateView(
        Long purchaseRecordId,
        Long productId,
        String productName,
        String sellerName,
        LocalDate purchasedOn,
        int purchasedQty,
        int receivedQty,
        int remainingQty,
        BigDecimal unitPrice
) {

    /** Query constructor — {@code remainingQty} is derived so the JPQL stays readable. */
    public PurchaseCandidateView(Long purchaseRecordId, Long productId, String productName,
                                 String sellerName, LocalDate purchasedOn, Integer purchasedQty,
                                 Long receivedQty, BigDecimal unitPrice) {
        this(purchaseRecordId, productId, productName, sellerName, purchasedOn,
                purchasedQty, receivedQty.intValue(), purchasedQty - receivedQty.intValue(), unitPrice);
    }
}
