package com.pms.dto.response;

import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동기화 트리거 응답 (POST /api/orders/sync).
 *
 * 동기화 결과 요약 + 동기화 직후 주문 목록을 함께 담아, 화면이 추가 GET 없이 즉시 갱신하게 한다
 * (orders 는 GET /api/orders 와 동일한 {@link OrderItemResponse}).
 */
@Getter
public class OrderSyncResponse {

    private final LocalDateTime syncedAt;
    private final int newOrders;
    private final int updatedOrders;
    private final int canceledUpdated;
    private final List<OrderItemResponse> orders;

    public OrderSyncResponse(OrderSyncResult result, List<OrderItemResponse> orders) {
        this.syncedAt = result.syncedAt();
        this.newOrders = result.newOrders();
        this.updatedOrders = result.updatedOrders();
        this.canceledUpdated = result.canceledUpdated();
        this.orders = orders;
    }
}
