package com.pms.repository;

import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 헤더 리포지토리 (FEATURE_2609_26 / PLAN D2).
 *
 * <p>⚠️ 엔티티 {@link Order} 는 {@code jakarta.persistence.criteria.Order}·
 * {@code org.springframework.data.domain.Sort.Order} 와 이름이 겹친다 — 정렬을 쓰는 파일에서 import 주의.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** UNIQUE 키(계정 + 플랫폼 주문번호)로 주문 헤더 조회 — 적재 upsert 의 멱등성 키. */
    Optional<Order> findByMarketplaceAccount_IdAndExternalOrderId(Long accountId, String externalOrderId);

    // ── 주문내역 조회 창 (FEATURE_2609_26 / 04) ──────────────────────────────
    // 반환은 라인이지만 <b>기준 컬럼이 orders.ordered_at</b> 이라 이 리포지토리가 소유한다.
    // (2609_26 이전에는 order_item 리포지토리가 paid_at 기준으로 갖고 있었다.)
    //
    // 동기화가 syncDays(주문 createdAt 기준) 밖 주문의 상태를 갱신하지 못해 stale 행이 남으므로,
    // 표시 쿼리도 같은 창으로 제한한다. ordered_at 이 null 인 주문(파싱 실패)은 필터에서 제외된다.
    // 단, GET /api/orders 는 기간을 명시하면 이 창을 벗어난 과거도 조회한다
    // (FEATURE_2609_08 D1·D7 — 화면이 stale 을 고지).

    /** 전체 주문 라인, from 이후(ordered_at) 최신순 — GET /api/orders. */
    @Query("SELECT l FROM OrderLine l JOIN l.order o WHERE o.orderedAt >= :from ORDER BY o.orderedAt DESC")
    List<OrderLine> findRecentOrders(@Param("from") LocalDateTime from);

    /** 셀러 단위 주문 라인, from 이후(ordered_at) 최신순 — GET /api/orders?sellerId=. */
    @Query("SELECT l FROM OrderLine l JOIN l.order o "
            + "WHERE o.marketplaceAccount.seller.id = :sellerId AND o.orderedAt >= :from "
            + "ORDER BY o.orderedAt DESC")
    List<OrderLine> findRecentOrdersBySeller(@Param("sellerId") Long sellerId,
                                             @Param("from") LocalDateTime from);

    /**
     * 기간 지정 주문 라인, [from, toExclusive) 최신순 — GET /api/orders?from=&to=.
     *
     * 상한이 <b>배타적(exclusive)</b> 인 이유: 호출자가 to+1일 00:00 을 넘긴다. {@code <=} + 23:59:59 는
     * 그 날 마지막 초에 결제된 주문을 놓치는 고전적 경계 버그라 쓰지 않는다(2609_08 D4).
     * ⚠️ 이 창은 동기화 창(syncDays)보다 넓을 수 있다 — 창 밖 행의 상태는 갱신되지 않은 값이다.
     */
    @Query("SELECT l FROM OrderLine l JOIN l.order o "
            + "WHERE o.orderedAt >= :from AND o.orderedAt < :toExclusive ORDER BY o.orderedAt DESC")
    List<OrderLine> findOrdersInPeriod(@Param("from") LocalDateTime from,
                                       @Param("toExclusive") LocalDateTime toExclusive);

    /** 셀러 + 기간 지정 주문 라인 — GET /api/orders?sellerId=&from=&to=. */
    @Query("SELECT l FROM OrderLine l JOIN l.order o "
            + "WHERE o.marketplaceAccount.seller.id = :sellerId "
            + "AND o.orderedAt >= :from AND o.orderedAt < :toExclusive ORDER BY o.orderedAt DESC")
    List<OrderLine> findOrdersInPeriodBySeller(@Param("sellerId") Long sellerId,
                                               @Param("from") LocalDateTime from,
                                               @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * 주문 라인이 존재하는 달과 건수(최신순) — GET /api/orders/months 의 원천.
     *
     * ordered_at 이 null 인 주문은 어느 달에도 속하지 않는다(집계 제외).
     * 🔴 JPQL 표준 함수 YEAR()/MONTH() 만 쓴다 — DATE_FORMAT 같은 MySQL 전용 함수는 H2 테스트에서 깨진다.
     * 🔴 건수는 <b>라인 수</b>다(목록이 라인 단위라 같은 입도를 유지한다).
     * 반환 row = {year, month, count} (숫자 타입은 Hibernate·DB 조합에 따라 다르므로 Number 로 받을 것).
     */
    @Query("SELECT YEAR(o.orderedAt), MONTH(o.orderedAt), COUNT(l) FROM OrderLine l JOIN l.order o "
            + "WHERE o.orderedAt IS NOT NULL "
            + "GROUP BY YEAR(o.orderedAt), MONTH(o.orderedAt) "
            + "ORDER BY YEAR(o.orderedAt) DESC, MONTH(o.orderedAt) DESC")
    List<Object[]> countByMonth();
}
