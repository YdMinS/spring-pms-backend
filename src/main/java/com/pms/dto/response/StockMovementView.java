package com.pms.dto.response;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the physical stock ledger (FEATURE_2609_28). Quantity is signed as stored.
 *
 * <p>{@code sellerId}/{@code sellerName} = whose stock moved (PLAN 2609_29 D4) — always present.
 */
public record StockMovementView(
        Long id,
        Long productId,
        String productName,
        Long sellerId,
        String sellerName,
        StockMovementType movementType,
        int quantity,
        StockReason reason,
        String reasonNote,
        BigDecimal unitPrice,
        Long orderLineId,
        Long orderClaimId,
        Long purchaseRecordId,
        LocalDate movedOn,
        String createdBy
) {
}
