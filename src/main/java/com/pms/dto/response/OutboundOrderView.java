package com.pms.dto.response;

import com.pms.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 출고 확인 화면의 주문 라인 1건 (FEATURE_2609_28 / PLAN D11).
 *
 * <p>출고는 <b>주문에서 출발</b>한다 — {@code order_line_id} 가 채워져야 나중에 원가 스냅샷이
 * "어느 주문으로 나갔는지"를 답할 수 있다. 물품에서 출발하면 수량만 맞고 그 연결을 잃는다.
 *
 * <p>⚠️ {@code sellerId}/{@code sellerName} 은 {@code order → marketplaceAccount → seller} 로
 * 유도한다(PLAN 2609_29 D4) — 재고는 판매자별로 갈리므로 출고 행에도 그 축이 실린다.
 * ⚠️ 정렬은 {@code orderedAt} 오름차순 — 오래된 주문부터 처리한다.
 */
public record OutboundOrderView(
        Long orderLineId,
        String externalOrderId,
        String itemName,
        OrderStatus status,
        LocalDateTime orderedAt,
        Long sellerId,
        String sellerName,
        int orderQty,
        List<OutboundProductLine> products) {
}
