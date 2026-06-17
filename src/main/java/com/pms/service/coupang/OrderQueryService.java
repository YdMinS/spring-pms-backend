package com.pms.service.coupang;

import com.pms.dto.response.OrderItemResponse;

import java.util.List;

/**
 * 동기화된 order_item 조회 (GET /api/orders, 화면 표시/검증용 read).
 */
public interface OrderQueryService {

    /** 주문 목록 (최신 결제순). sellerId 가 null 이면 전체, 있으면 해당 셀러만. */
    List<OrderItemResponse> list(Long sellerId);
}
