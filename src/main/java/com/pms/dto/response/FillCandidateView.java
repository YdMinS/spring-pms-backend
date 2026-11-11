package com.pms.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * One product of the 「채울까요?」 window after an intake save (FEATURE_2611_22 D19 · D23 · D30 · D31 · D32).
 *
 * @param rowCount      N of 「입고 기록 없이 나간 출고 N건(M개)이 있습니다.」 — the order outbound rows without a lot
 * @param emptyQuantity M of the same text — Σ of their pieces
 * @param lots          the saved lots of this product, in save order — each one is a part of the window
 */
public record FillCandidateView(
        Long productId,
        String productName,
        int rowCount,
        int emptyQuantity,
        List<Lot> lots
) {
    /**
     * @param sellerName the lot owner; null = 「영수증 미등록」 (an undecided lot)
     * @param quantity   what is left of the lot
     * @param rows       every row of {@code rowCount}, D17 order for this lot: the lot owner's rows first, then the
     *                   others, each oldest first (day, then record order)
     */
    public record Lot(
            Long lotId,
            LocalDate movedOn,
            String sellerName,
            int quantity,
            List<Row> rows
    ) {}

    /**
     * @param sellerName      the order seller
     * @param emptyQuantity   the row's pieces without a lot
     * @param checkedQuantity D23 · D32: the pieces checked at first for this lot; 0 = not checked
     */
    public record Row(
            Long movementId,
            String orderNumber,
            LocalDate movedOn,
            String sellerName,
            int emptyQuantity,
            int checkedQuantity
    ) {}
}
