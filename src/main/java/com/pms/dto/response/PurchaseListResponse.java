package com.pms.dto.response;

import java.util.List;

/**
 * Purchase list tab, one response (FEATURE_2610_09 D57 · D67 · D78).
 * items: rows with a share still to buy. skippedToday: rows whose remaining shares were all put off with
 * 「내일 매입」 today (the 「오늘 넘김」 group). unmappedOrders: paid orders whose option is unregistered or whose BOM is empty.
 */
public record PurchaseListResponse(
        List<PurchaseProductGroup> items,
        List<PurchaseProductGroup> skippedToday,
        List<UnmappedOrder> unmappedOrders
) {}
