package com.pms.repository;

import com.pms.domain.ClaimType;
import com.pms.domain.OrderClaim;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * order_claim 접근 (FEATURE_2609_18).
 *
 * ⚠️ 조회 메서드는 전부 {@code @EntityGraph} 로 계정·셀러·주문라인을 즉시 로딩한다 — open-in-view=false
 * 라 응답 매핑 시점에 지연로딩이면 LazyInitializationException 이 난다.
 * ⚠️ {@code @TenantId} 가 SELECT 를 자동 필터하므로 쿼리에 tenant 조건을 직접 넣지 않는다.
 */
public interface OrderClaimRepository extends JpaRepository<OrderClaim, Long> {

    /** UNIQUE 3키로 기존 클레임 조회 (동기화 upsert 의 멱등성 키). */
    Optional<OrderClaim> findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
            Long accountId, String externalClaimId, String externalItemId);

    /** 종류 + 접수일 창 [start, end) 의 클레임, 최신 접수순 — GET /api/claims. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderItem"})
    @Query("select c from OrderClaim c where c.claimType = :type "
            + "and c.receivedAt >= :start and c.receivedAt < :end order by c.receivedAt desc")
    List<OrderClaim> findInPeriod(@Param("type") ClaimType type,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    /** 셀러 필터 버전 — GET /api/claims?sellerId=. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderItem"})
    @Query("select c from OrderClaim c where c.claimType = :type "
            + "and c.marketplaceAccount.seller.id = :sellerId "
            + "and c.receivedAt >= :start and c.receivedAt < :end order by c.receivedAt desc")
    List<OrderClaim> findInPeriodBySeller(@Param("type") ClaimType type,
                                          @Param("sellerId") Long sellerId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /** 단건 상세 — GET /api/claims/{id}. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderItem"})
    Optional<OrderClaim> findWithAccountById(Long id);
}
