package com.pms.dto.response;

import java.util.List;

/**
 * product 그룹 토글을 펼치면 보이는 기여 라인 1개.
 * source: "ORDER"(주문 라인) | "MANUAL"(수동 라인). 수동 라인은 orderItemId/externalOrderId 가 null.
 */
public record PurchaseLine(
        Long itemId,
        Long orderItemId,
        String source,
        String externalOrderId,
        int autoQty,
        int manualQty,
        int purchasedQty,
        List<PurchaseRecordView> records
) {}
