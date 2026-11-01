package com.pms.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 「그날 입출고 내역」 (FEATURE_2610_19 D119 · D122 · D123 · D147).
 *
 * @param unregisteredProductCount 「영수증 미등록 N건」 — products of that day with an undecided quantity left (D147)
 * @param unregistered             those products with the quantity a receipt can still take (D121 ①)
 * @param rows                     every ledger row moved that day, record order
 */
public record StockDailyView(
        LocalDate date,
        int unregisteredProductCount,
        List<UnregisteredProduct> unregistered,
        List<StockDailyRowView> rows
) {
    /** One product a receipt can still be assigned to. */
    public record UnregisteredProduct(Long productId, String productName, int remainingQty) {}
}
