package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * One order of the 「최근 14일 출고 주문」 list (FEATURE_2611_34 D29 · D39 · D43).
 *
 * @param itemName     the item name of the order's first shipped line
 * @param claimPending a return or exchange claim on its shipped lines is still pending (R8)
 */
public record ShippedOrderView(
        Long orderId,
        String externalOrderId,
        LocalDateTime orderedAt,
        String platform,
        String accountAlias,
        String sellerName,
        String itemName,
        boolean claimPending
) {
}
