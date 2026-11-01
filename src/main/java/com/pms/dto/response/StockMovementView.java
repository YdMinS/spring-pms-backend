package com.pms.dto.response;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the physical stock ledger (FEATURE_2609_28). Quantity is signed as stored.
 *
 * <p>{@code sellerId}/{@code sellerName} = whose stock moved (PLAN 2609_29 D4). null = 「판매자 미정」: an undecided
 * intake row or an outbound row that used one (FEATURE_2610_19 D105 · D124).
 *
 * <p>{@code notices} = the outbound confirm's 「○○ 재고 N개 사용(채무 ₩M)」 lines for this row (FEATURE_2610_19
 * D49 · D137); empty everywhere else.
 *
 * <p>{@code imageUrl} = the product's representative photo ({@code product.image_url}) as stored; null = none
 * (FEATURE_2611_02 D8).
 */
public record StockMovementView(
        Long id,
        Long productId,
        String productName,
        String imageUrl,
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
        String createdBy,
        List<String> notices
) {
}
