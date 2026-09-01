package com.pms.repository;

import com.pms.domain.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    /** 전체 주문 목록, from 이후(paidAt) 최신순 — GET /api/orders. */
    @Query("SELECT o FROM OrderItem o WHERE o.paidAt >= :from ORDER BY o.paidAt DESC")
    List<OrderItem> findRecentOrders(@Param("from") LocalDateTime from);

    /** 셀러 단위 주문 목록, from 이후(paidAt) 최신순 — GET /api/orders?sellerId=. */
    @Query("SELECT o FROM OrderItem o WHERE o.marketplaceAccount.seller.id = :sellerId AND o.paidAt >= :from ORDER BY o.paidAt DESC")
    List<OrderItem> findRecentOrdersBySeller(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from);

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

    /**
     * 아직 발송 전(status IN statuses) 상태로 남아 있는 이 계정 주문의 고유 주문번호 목록.
     *
     * 취소 보정 재조정용 — 판매자 품절취소(쿠팡 receiptType=RETURN)는 cancelType=CANCEL 조회에
     * 안 잡혀 발송전 주문이 취소돼도 로컬 status/cancel_count 가 갱신되지 않는다. 이 주문번호들을
     * returnRequests?orderId= (전체 타입) 로 재조회해 취소를 반영한다.
     */
    @Query("SELECT DISTINCT o.externalOrderId FROM OrderItem o " +
            "WHERE o.marketplaceAccount.id = :accountId AND o.status IN :statuses")
    List<String> findDistinctExternalOrderIdByAccountAndStatusIn(
            @Param("accountId") Long accountId, @Param("statuses") Collection<String> statuses);
}
