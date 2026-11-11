package com.pms.dto.request;

import java.util.List;

/**
 * [채우기] of the 「채울까요?」 window and of 「일별 입출고 내역」 [입고로 채우기] (FEATURE_2611_22 D3 · D15 · D19 · D23 ·
 * D32): per lot, the order outbound rows without a lot it fills and how many pieces each.
 *
 * @param fills one entry per lot, in the order they are applied
 */
public record StockFillRequest(
        List<Fill> fills
) {
    /**
     * @param lotId the incoming row that fills (a stock-in or an adjust +, not a return — D4 · D18)
     * @param lines the rows it fills, in the order they are applied
     */
    public record Fill(
            Long lotId,
            List<Line> lines
    ) {}

    /**
     * @param movementId an order outbound row without a lot; a row filled in part splits, and a later fill of the same
     *                   row in this request applies to what is still empty (D8 · D32)
     * @param quantity   pieces to fill, 1 or more
     */
    public record Line(
            Long movementId,
            Integer quantity
    ) {}
}
