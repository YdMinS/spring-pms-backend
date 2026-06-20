package com.pms.dto.response;

import java.util.List;

/**
 * "오늘 구매 목록" 화면 1회 응답.
 * items: 정상 매핑된 구성품 단위 집계(잔여>0만). unmappedOrders: 옵션 미등록/BOM 빈 주문.
 */
public record PurchaseListResponse(
        List<PurchaseProductGroup> items,
        List<UnmappedOrder> unmappedOrders
) {}
