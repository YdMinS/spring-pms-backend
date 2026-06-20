package com.pms.dto.response;

/**
 * 옵션 미등록(매핑 안 됨) 또는 BOM 빈 ACCEPT 주문. BOM 전개 불가라 옵션(external_item_id) 단위로 노출해
 * 사용자가 옵션을 등록하거나 수동 처리하도록 한다.
 */
public record UnmappedOrder(
        String externalItemId,
        String itemName,
        int purchasableQty,
        int orderCount
) {}
