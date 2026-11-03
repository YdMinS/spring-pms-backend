package com.pms.service.coupang;

import com.pms.domain.OrderLine;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads of the synced order lines — GET /api/orders and the line rows of the shared order detail (FEATURE_2611_07 /
 * PLAN D17). Read-only, for screen display and checks.
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

    /**
     * The given lines as order list rows, in the given order — the same mapping {@link #list} uses. The shared order
     * detail (FEATURE_2611_07 / PLAN D17) calls it so its lines carry exactly the fields of one list row.
     */
    List<OrderItemResponse> rows(List<OrderLine> lines);

    /** 주문이 존재하는 달과 건수(최신순) — GET /api/orders/months. 판매자 필터와 무관한 전체 기준(D17). */
    List<OrderMonthResponse> months();
}
