package com.pms.repository;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 라인 리포지토리 (FEATURE_2609_26 / PLAN D2).
 *
 * <p>🔴 전송 레그(발송처리·발주처리·주문취소·송장시트)는 <b>트랜잭션 밖</b>에서 계정·박스·주문번호를
 * 읽는다(open-in-view=false). 그래서 그 경로가 쓰는 finder 는 전부 {@code @EntityGraph} 로
 * {@code order → marketplaceAccount}(필요하면 {@code seller})와 {@code orderShipment} 를 즉시 로딩한다 —
 * <b>제거하면 LazyInitializationException 이 난다</b>.
 */
public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    /**
     * 금액 백필 배치 — 금액이 비어 있는 라인을 id 오름차순 keyset 페이징으로 읽는다.
     *
     * <p>⚠️ 항상 첫 페이지를 다시 읽는 방식은 쓰지 않는다: 파싱에 실패해 계속 null 로 남는 행이
     * 매 회차 다시 뽑혀 무한 루프가 된다. {@code lastId} 로 전진하면 각 행을 정확히 한 번만 본다.
     */
    @Query("SELECT l FROM OrderLine l WHERE l.unitPrice IS NULL AND l.id > :lastId ORDER BY l.id ASC")
    List<OrderLine> findAmountBackfillBatch(@Param("lastId") Long lastId, Pageable pageable);

    /**
     * 전 테넌트 id 목록 — {@code @TenantId} 필터를 우회하는 native 쿼리.
     *
     * <p>비-웹 컨텍스트(마이그레이션 러너)는 TenantContext 가 비어 있어 일반 조회가 NO_TENANT 로
     * 0건이 된다. 러너는 이 목록을 돌며 테넌트를 명시 set 한다(backend CLAUDE.md §9).
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM order_line", nativeQuery = true)
    List<Long> findDistinctTenantIds();

    // ── 구매목록(사입 대상) ────────────────────────────────────────────────
    // 동기화 창(syncDays) 밖 주문은 상태가 갱신되지 않아 stale PAID 로 남을 수 있으므로,
    // 구매목록 추출도 같은 창(ordered_at 기준)으로 제한한다.

    /** 상태별 주문 라인, from 이후(ordered_at) — 구매 목록 추출 (status = PAID). */
    @Query("SELECT l FROM OrderLine l JOIN l.order o WHERE l.status = :status AND o.orderedAt >= :from")
    List<OrderLine> findRecentByStatus(@Param("status") OrderStatus status,
                                       @Param("from") LocalDateTime from);

    /** 셀러 + 상태별 주문 라인, from 이후(ordered_at) — 셀러 필터 구매 목록 추출. */
    @Query("SELECT l FROM OrderLine l JOIN l.order o WHERE l.status = :status "
            + "AND o.marketplaceAccount.seller.id = :sellerId AND o.orderedAt >= :from")
    List<OrderLine> findRecentByStatusAndSeller(@Param("status") OrderStatus status,
                                                @Param("sellerId") Long sellerId,
                                                @Param("from") LocalDateTime from);

    // ── 전송 레그 (트랜잭션 밖 · @EntityGraph 필수) ─────────────────────────

    /**
     * 주문번호(플랫폼 orderId)로 그 주문의 모든 라인 조회 — 발송처리 전개용.
     *
     * <p>계정(자격증명)·배송 묶음(박스 id)·주문 헤더(주문번호)를 트랜잭션 밖에서 읽으므로 전부 eager 다.
     */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount", "orderShipment"})
    @Query("SELECT l FROM OrderLine l WHERE l.order.externalOrderId = :externalOrderId")
    List<OrderLine> findByExternalOrderId(@Param("externalOrderId") String externalOrderId);

    /**
     * 단건 송장시트·단건 발송처리용 조회 — 계정과 <b>seller</b> 까지 eager fetch 한다.
     *
     * <p>시트 생성은 외부 HTTP 를 도는 경로라 {@code seller.getSellerName()} 이 지연로딩이면 터진다.
     */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount",
            "order.marketplaceAccount.seller", "orderShipment"})
    Optional<OrderLine> findWithAccountAndSellerById(Long id);

    /**
     * id 목록으로 주문 라인 조회 — 발주처리·주문취소 전개용.
     *
     * <p>seller 는 쓰지 않으므로 계정까지만 즉시 로딩한다(2609_17 D1).
     */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount", "orderShipment"})
    List<OrderLine> findWithAccountByIdIn(List<Long> ids);
}
