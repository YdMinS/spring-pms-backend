package com.pms.dto.response;

import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동기화 트리거 응답 (POST /api/orders/sync).
 *
 * ⚠️ 필드를 손으로 복사하는 DTO 다 — {@link OrderSyncResult} 에 필드를 더해도 여기 추가하지 않으면
 * 이 엔드포인트 응답에는 실리지 않는다(POST /api/orders/sync/period 는 record 를 그대로 직렬화한다).
 * {@code syncedAt} 은 {@code null} 일 수 있다 — 전 채널이 건너뛰어 실제로 조회하지 않은 회차다(D9).
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
    /** 이미 같은 채널이 돌고 있어 쿠팡을 치지 않은 채널 수(FEATURE_2609_48 / D5). 실패가 아니다. */
    private final int skippedAccounts;
    private final List<OrderItemResponse> orders;

    public OrderSyncResponse(OrderSyncResult result, List<OrderItemResponse> orders) {
        this.syncedAt = result.syncedAt();
        this.newOrders = result.newOrders();
        this.updatedOrders = result.updatedOrders();
        this.canceledUpdated = result.canceledUpdated();
        this.skippedAccounts = result.skippedAccounts();
        this.orders = orders;
    }
}
