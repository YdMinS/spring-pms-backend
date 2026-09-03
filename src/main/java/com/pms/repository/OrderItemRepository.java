package com.pms.repository;

import com.pms.domain.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** UNIQUE 4키로 기존 주문 라인 조회 (동기화 upsert 의 멱등성 키). */
    Optional<OrderItem> findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
            Long accountId, String boxId, String orderId, String itemId);

    // ── 조회/구매목록 윈도우 필터 ──────────────────────────────────────────────
    // 동기화가 syncDays(주문 createdAt 기준) 밖 주문의 status 를 갱신하지 못해 stale 행이 남으므로,
    // 표시 쿼리도 같은 윈도우로 제한한다. order_item 엔 주문 createdAt 이 없어 paidAt 을 기준으로 쓴다
    // (paidAt 이 null 인 라인 — 파싱 실패 — 은 필터에서 제외된다).
    // 단, GET /api/orders 는 기간을 명시하면 이 창을 벗어난 과거도 조회한다(FEATURE_2609_08 D1·D7 — 화면이 stale 을 고지).

    /** 전체 주문 목록, from 이후(paidAt) 최신순 — GET /api/orders. */
    @Query("SELECT o FROM OrderItem o WHERE o.paidAt >= :from ORDER BY o.paidAt DESC")
    List<OrderItem> findRecentOrders(@Param("from") LocalDateTime from);

    /** 셀러 단위 주문 목록, from 이후(paidAt) 최신순 — GET /api/orders?sellerId=. */
    @Query("SELECT o FROM OrderItem o WHERE o.marketplaceAccount.seller.id = :sellerId AND o.paidAt >= :from ORDER BY o.paidAt DESC")
    List<OrderItem> findRecentOrdersBySeller(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from);

    /**
     * 기간 지정 주문 목록, [from, toExclusive) 최신순 — GET /api/orders?from=&to=.
     *
     * 상한이 **배타적(exclusive)** 인 이유: 호출자가 to+1일 00:00 을 넘긴다. `<=` + 23:59:59 는
     * 그 날 마지막 초에 결제된 주문을 놓치는 고전적 경계 버그라 쓰지 않는다(PLAN D4).
     * ⚠️ 이 창은 동기화 창(syncDays)보다 넓을 수 있다 — 창 밖 행의 status 는 갱신되지 않은 값이다.
     */
    @Query("SELECT o FROM OrderItem o WHERE o.paidAt >= :from AND o.paidAt < :toExclusive ORDER BY o.paidAt DESC")
    List<OrderItem> findOrdersInPeriod(@Param("from") LocalDateTime from,
                                       @Param("toExclusive") LocalDateTime toExclusive);

    /** 셀러 + 기간 지정 주문 목록 — GET /api/orders?sellerId=&from=&to=. */
    @Query("SELECT o FROM OrderItem o WHERE o.marketplaceAccount.seller.id = :sellerId "
            + "AND o.paidAt >= :from AND o.paidAt < :toExclusive ORDER BY o.paidAt DESC")
    List<OrderItem> findOrdersInPeriodBySeller(@Param("sellerId") Long sellerId,
                                               @Param("from") LocalDateTime from,
                                               @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * 주문이 존재하는 달과 건수(최신순) — GET /api/orders/months 의 원천.
     *
     * paidAt 이 null 인 라인은 어느 달에도 속하지 않는다(집계 제외).
     * JPQL 표준 함수 YEAR()/MONTH() 만 쓴다 — DATE_FORMAT 같은 MySQL 전용 함수는 H2 테스트에서 깨진다.
     * 반환 row = {year, month, count} (숫자 타입은 Hibernate·DB 조합에 따라 다르므로 Number 로 받을 것).
     */
    @Query("SELECT YEAR(o.paidAt), MONTH(o.paidAt), COUNT(o) FROM OrderItem o "
            + "WHERE o.paidAt IS NOT NULL "
            + "GROUP BY YEAR(o.paidAt), MONTH(o.paidAt) "
            + "ORDER BY YEAR(o.paidAt) DESC, MONTH(o.paidAt) DESC")
    List<Object[]> countByMonth();

    /** 상태별 주문 라인, from 이후(paidAt) — 구매 목록 추출 (status="ACCEPT"). */
    @Query("SELECT o FROM OrderItem o WHERE o.status = :status AND o.paidAt >= :from")
    List<OrderItem> findRecentByStatus(@Param("status") String status, @Param("from") LocalDateTime from);

    /** 셀러 + 상태별 주문 라인, from 이후(paidAt) — 셀러 필터 구매 목록 추출. */
    @Query("SELECT o FROM OrderItem o WHERE o.status = :status AND o.marketplaceAccount.seller.id = :sellerId AND o.paidAt >= :from")
    List<OrderItem> findRecentByStatusAndSeller(@Param("status") String status, @Param("sellerId") Long sellerId, @Param("from") LocalDateTime from);

    /**
     * 주문번호(쿠팡 orderId)로 그 주문의 모든 라인(박스의 전체 vendorItemId) 조회 — 발송처리 전개용.
     *
     * 발송처리 서비스는 @Transactional 없이(open-in-view=false) account.getPlatform()/getVendorId()/
     * getAccessKey() 등을 읽으므로, marketplaceAccount 를 즉시 로딩해 LazyInitializationException 방지.
     */
    @EntityGraph(attributePaths = "marketplaceAccount")
    List<OrderItem> findByExternalOrderId(String externalOrderId);

    /**
     * 단건 송장시트용 조회 — marketplaceAccount 와 seller 를 함께 eager fetch 한다.
     *
     * 시트 생성은 @Transactional 없이 외부 HTTP 를 도는 경로라(open-in-view=false),
     * account.getSeller().getSellerName() 이 지연로딩이면 LazyInitializationException 이 난다.
     * 기존 findByExternalOrderId 는 seller 를 포함하지 않아 재사용할 수 없다.
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    Optional<OrderItem> findWithAccountAndSellerById(Long id);
}
