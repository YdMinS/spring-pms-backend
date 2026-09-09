package com.pms.dto.response;

/**
 * product 그룹 토글을 펼치면 보이는 기여 라인 1개 (PLAN 2609_29 D7·D8).
 *
 * <p>source: "ORDER"(주문 라인) | "MANUAL"(수동 라인). 수동 라인은
 * {@code orderLineId}·{@code externalOrderId} 와 채널 3필드
 * ({@code marketplaceAccountId}·{@code sellerName}·{@code platform})가 <b>전부 null</b> 이다 —
 * 화면은 그것을 "수동" 칩으로 그린다.
 *
 * <p>🔴 라인에는 구매수량도 구매이력도 없다(D7). 구매기록이 주문을 모르므로(D3) 계산 자체가 불가능하고,
 * 사용자도 "각 주문이 자신을 위한 재고가 입고됐는지 알 필요 없다"고 확정했다. 필요수량만 갖는다.
 */
public record PurchaseLine(
        Long itemId,
        Long orderLineId,
        String source,
        String externalOrderId,
        Long marketplaceAccountId,
        String sellerName,
        String platform,
        int autoQty,
        int manualQty,
        int neededQty
) {}
