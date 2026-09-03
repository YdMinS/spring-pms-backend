package com.pms.service.coupang;

import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 동기화된 order_item 조회 (GET /api/orders, 화면 표시/검증용 read).
 */
public interface OrderQueryService {

    /**
     * 주문 목록 (최신 결제순).
     *
     * @param sellerId null 이면 전체
     * @param from     결제일 시작(당일 포함). {@code to} 와 <b>함께</b> 주거나 둘 다 null
     * @param to       결제일 끝(<b>당일 포함</b> — 구현이 +1일 00:00 으로 변환)
     * @throws IllegalArgumentException 하나만 주거나 from &gt; to (→ 400)
     *
     * 둘 다 null 이면 기본 창 = 오늘 − {@code coupang.sync-days} (기존 동작).
     */
    List<OrderItemResponse> list(Long sellerId, LocalDate from, LocalDate to);

    /** 주문이 존재하는 달과 건수(최신순) — GET /api/orders/months. 판매자 필터와 무관한 전체 기준(D17). */
    List<OrderMonthResponse> months();
}
