package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A purchase whose goods have not (fully) been checked in yet (FEATURE_2609_28 / prompt 04 Step 6-1).
 *
 * <p>STOCK_IN + PURCHASE requires a {@code purchaseRecordId}, so without this list a purchase
 * check-in could not be created at all.
 *
 * <p>⚠️ {@code unitPrice} may be null ("amount unknown") — the row stays in the list, because the
 * goods can arrive before the receipt is known.
 * {@code externalOrderId} is null for stock-replenishment purchases that are not tied to an order.
 */
public record PurchaseCandidateView(
        Long purchaseRecordId,
        Long productId,
        String productName,
        LocalDate purchasedOn,
        int purchasedQty,
        int receivedQty,
        int remainingQty,
        BigDecimal unitPrice,
        String externalOrderId
) {

    /** Query constructor — {@code remainingQty} is derived so the JPQL stays readable. */
    public PurchaseCandidateView(Long purchaseRecordId, Long productId, String productName,
                                 LocalDate purchasedOn, Integer purchasedQty, Long receivedQty,
                                 BigDecimal unitPrice, String externalOrderId) {
        this(purchaseRecordId, productId, productName, purchasedOn,
                purchasedQty, receivedQty.intValue(), purchasedQty - receivedQty.intValue(),
                unitPrice, externalOrderId);
    }
}
