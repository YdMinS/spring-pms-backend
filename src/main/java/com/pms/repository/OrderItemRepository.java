package com.pms.repository;

import com.pms.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** UNIQUE 4키로 기존 주문 라인 조회 (동기화 upsert 의 멱등성 키). */
    Optional<OrderItem> findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
            Long accountId, String boxId, String orderId, String itemId);

    /** 셀러 단위 주문 목록 (최신 결제순) — GET /api/orders?sellerId=. */
    List<OrderItem> findByMarketplaceAccount_Seller_IdOrderByPaidAtDesc(Long sellerId);

    /** 전체 주문 목록 (최신 결제순) — GET /api/orders. */
    List<OrderItem> findAllByOrderByPaidAtDesc();
}
