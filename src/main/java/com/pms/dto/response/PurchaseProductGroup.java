package com.pms.dto.response;

import java.util.List;

/**
 * 구성품(Product) 단위 집계 한 줄. remainingQty = neededQty − purchasedQty.
 * 목록에는 remainingQty > 0 인 그룹만 포함된다. lines 는 그 product 에 기여한 모든 라인.
 */
public record PurchaseProductGroup(
        Long productId,
        String productName,
        int neededQty,
        int purchasedQty,
        int remainingQty,
        List<PurchaseLine> lines
) {}
