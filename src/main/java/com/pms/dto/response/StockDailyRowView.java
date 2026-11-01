package com.pms.dto.response;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;

import java.math.BigDecimal;
import java.util.List;

/**
 * One ledger row of 「그날 입출고 내역」 (FEATURE_2610_19 D119 · D123 · D124 · D128 · D130).
 *
 * @param sellerName    outbound = the order seller (D124); undecided intake = null; other rows = the row's seller
 * @param owners        undecided intake only: the assigned owners as 「{seller} {qty}」 (D130)
 * @param undecidedLeft undecided intake only: what no receipt took yet (D121 ⑤); null for other rows
 * @param orderNumber   outbound only
 * @param tags          outbound only: 「{seller} 재고 사용」 · 「영수증 미등록 재고 사용」 (D124 · D128)
 * @param amount        purchase-price amount (D123); null = 「미기입」. Never a selling price (D122)
 */
public record StockDailyRowView(
        Long movementId,
        StockMovementType movementType,
        StockReason reason,
        Long productId,
        String productName,
        int quantity,
        String sellerName,
        List<String> owners,
        Integer undecidedLeft,
        String orderNumber,
        List<String> tags,
        BigDecimal amount
) {}
