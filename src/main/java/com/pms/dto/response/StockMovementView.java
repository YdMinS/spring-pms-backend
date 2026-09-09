package com.pms.dto.response;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of the physical stock ledger (FEATURE_2609_28). Quantity is signed as stored. */
public record StockMovementView(
        Long id,
        Long productId,
        String productName,
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
