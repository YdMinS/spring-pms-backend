package com.pms.repository;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.OrderClaim;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    /**
     * UNIQUE 4키로 기존 클레임 조회 (동기화 upsert 의 멱등성 키).
     *
     * ⚠️ {@code claimType} 이 키에 들어간다(D24) — 반품 {@code receiptId} 와 교환 {@code exchangeId} 는
     * 다른 시퀀스라, 빼면 값이 겹칠 때 교환이 반품 행을 덮어쓴다.
     */
    Optional<OrderClaim> findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
            Long accountId, ClaimType claimType, String externalClaimId, String externalItemId);

    /** 종류 + 접수일 창 [start, end) 의 클레임, 최신 접수순 — GET /api/claims. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderLine"})
    @Query("select c from OrderClaim c where c.claimType = :type "
            + "and c.receivedAt >= :start and c.receivedAt < :end order by c.receivedAt desc")
    List<OrderClaim> findInPeriod(@Param("type") ClaimType type,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    /** 셀러 필터 버전 — GET /api/claims?sellerId=. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderLine"})
    @Query("select c from OrderClaim c where c.claimType = :type "
            + "and c.marketplaceAccount.seller.id = :sellerId "
            + "and c.receivedAt >= :start and c.receivedAt < :end order by c.receivedAt desc")
    List<OrderClaim> findInPeriodBySeller(@Param("type") ClaimType type,
                                          @Param("sellerId") Long sellerId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /**
     * 아직 주문 라인이 붙지 않았고 시도 상한에 안 걸린 클레임 (04 백필 대상).
     * 최신 접수순 — 오래된 건일수록 쿠팡에서 사라졌을 확률이 높아 우선순위가 낮다.
     *
     * ⚠️ {@code @EntityGraph} 를 붙이지 않는다 — 백필은 id·externalOrderId·externalBoxId·externalItemId
     * 만 읽고 응답에 싣지 않으므로 연관을 즉시 로딩할 이유가 없다(다른 조회 메서드와 다른 점).
     * ⚠️ {@code externalOrderId is not null} — 조회 키가 없는 건은 경로에 null 이 박혀 실패만 하고
     * 시도횟수를 소모한다. 대상에서 아예 뺀다.
     */
    @Query("select c from OrderClaim c where c.marketplaceAccount.id = :accountId "
            + "and c.orderLine is null and c.orderItemMatchAttempts < :maxAttempts "
            + "and c.externalOrderId is not null "
            + "and c.status <> com.pms.domain.ClaimStatus.STALE "
            + "order by c.receivedAt desc")
    List<OrderClaim> findUnlinked(@Param("accountId") Long accountId,
                                  @Param("maxAttempts") int maxAttempts,
                                  Pageable pageable);

    /**
     * 미완결 클레임(05 의 추적 대상) — 접수 오래된 순.
     *
     * 종결 집합은 호출자가 {@link com.pms.domain.ClaimStatus#closedStatuses()} 로 넘긴다 — 여기에
     * 목록을 박으면 "무엇이 종결인가"가 두 벌이 된다.
     * ⚠️ {@code @EntityGraph} 를 붙이지 않는다 — 추적은 id·status·receivedAt 만 읽고 응답에 싣지 않는다.
     * 정렬이 오래된 순인 이유: 맨 앞이 곧 슬라이스의 시작일이다.
     */
    @Query("select c from OrderClaim c where c.marketplaceAccount.id = :accountId "
            + "and c.claimType = :type and c.status not in :closed order by c.receivedAt asc")
    List<OrderClaim> findOpen(@Param("accountId") Long accountId,
                              @Param("type") ClaimType type,
                              @Param("closed") Collection<ClaimStatus> closed);

    /**
     * 같은 접수의 <b>형제 라인 전체</b> — 액션 단건 경로의 중복 가드·수량 합계용 (FEATURE_2609_21 D6).
     *
     * <p>쿠팡 액션의 식별자는 {@code receiptId}(접수)인데 우리 {@code order_claim} 은 라인 단위다.
     * 한 접수에 라인이 3개면 claim 행도 3개고 어느 행에서 눌러도 접수 전체에 적용된다 — 그래서
     * 가드도 수량 합계도 이 목록을 기준으로 한다.
     *
     * ⚠️ {@link #findSiblingsBulk} 와 <b>조건이 같아야 한다</b>(이쪽은 claimIds 가 1개이고 계정 조건이
     * SQL 에 있는 형태). 갈리면 목록의 버튼과 서버 409 가 어긋난다.
     * ⚠️ {@code @EntityGraph} 를 붙이지 않는다 — 가드는 id·quantity 만 읽고 응답에 싣지 않는다.
     */
    @Query("select c from OrderClaim c where c.marketplaceAccount.id = :accountId "
            + "and c.claimType = :type and c.externalClaimId = :claimId")
    List<OrderClaim> findSiblings(@Param("accountId") Long accountId,
                                  @Param("type") ClaimType type,
                                  @Param("claimId") String claimId);

    /**
     * 형제 라인 일괄 조회 — 목록의 {@code availableActions} 계산용 (FEATURE_2609_21 Step 8).
     *
     * <p>(account, type, claimId) 튜플 IN 은 JPA 로 표현할 수 없으므로 (type, claimId IN) 으로 넓게
     * 긁고 <b>계정은 메모리에서 좁힌다</b> — {@code externalClaimId} 는 계정 간 유일하지 않다.
     */
    @Query("select c from OrderClaim c where c.claimType = :type and c.externalClaimId in :claimIds")
    List<OrderClaim> findSiblingsBulk(@Param("type") ClaimType type,
                                      @Param("claimIds") List<String> claimIds);

    /** 단건 상세 — GET /api/claims/{id}. */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderLine"})
    Optional<OrderClaim> findWithAccountById(Long id);

    /**
     * 미완결 클레임 건수 — 알림 배지(FEATURE_2609_49 / D9·D14).
     *
     * <p>🔴 <b>타입 무관·기간 무관</b>이다. 목록 화면의 기본값(반품 탭 + 최근 sync-days)과 일부러 다르다 —
     * 배지의 질문은 "처리할 일이 몇 건인가"라, 20일 전에 접수돼 아직 안 끝난 반품도 여전히 처리할 일이다.
     * 기간으로 자르면 오래된 미처리 건이 배지에서 사라지는데, 그 건들이야말로 배지의 존재 이유다.
     * 실질 상한은 {@code ClaimStaleSweeper}(claim-stale-days 30일)가 만든다.
     *
     * <p>호출부는 {@link ClaimStatus#closedStatuses()} 를 넘긴다 — 상태 목록을 쿼리에 나열하면
     * "무엇이 종결인가" 의 정의가 두 벌이 된다.
     * ⚠️ {@code @TenantId} 가 자동 적용된다 — 테넌트 조건을 손으로 붙이지 않는다.
     */
    long countByStatusNotIn(Collection<ClaimStatus> statuses);

    /**
     * 미완결 클레임 한 장 — 알림 목록(FEATURE_2609_51 / D8·D12). 기간·커서·건수 상한이 모두 걸린다.
     *
     * <p>🔴 <b>동률을 쿼리에서 자른다</b>: {@code (t < :cursorTime or (t = :cursorTime and id < :cursorTieId))}.
     * "{@code <=} 로 넉넉히 읽고 서비스가 여유분(+N)으로 거르는" 방식을 쓰지 말 것 — 같은 시각 건이
     * 여유분보다 많으면(동기화는 초 단위로 뭉쳐 들어온다) 그 행들이 <b>조용히 사라진다.</b>
     * {@code cursorTieId} 는 서비스가 소스별로 계산해 넘긴다(커서와 같은 종류면 커서 id, 종류 순서상
     * 뒤면 {@code Long.MAX_VALUE}, 앞이면 {@code Long.MIN_VALUE}).
     *
     * <p>⚠️ {@code Pageable} 로 정확히 페이지 크기만 읽는다 — <b>기간 안 전부를 읽지 않는다.</b>
     * ⚠️ {@code @EntityGraph} 가 필요하다: 응답이 {@code seller.getSellerName()} 을 읽는데
     * open-in-view=false 라 지연로딩이면 LazyInitializationException 이다(2026-07-15 실제 장애).
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    @Query("select c from OrderClaim c where c.status not in :closed and c.receivedAt >= :from "
            + "and (c.receivedAt < :cursorTime or (c.receivedAt = :cursorTime and c.id < :cursorTieId)) "
            + "order by c.receivedAt desc, c.id desc")
    List<OrderClaim> findOpenForAlerts(@Param("closed") Collection<ClaimStatus> closed,
                                       @Param("from") LocalDateTime from,
                                       @Param("cursorTime") LocalDateTime cursorTime,
                                       @Param("cursorTieId") Long cursorTieId,
                                       Pageable pageable);

    /**
     * 종 배지용 미완결 클레임 건수 (FEATURE_2609_51 / D3).
     *
     * <p>🔴 목록과 <b>같은 기간 조건</b>이어야 한다 — 한쪽만 기간을 걸면 "3건이라는데 2건만 보인다"가 된다.
     * 🔴 사이드바 배지용 {@link #countByStatusNotIn}(기간 무관)은 <b>건드리지 말 것</b> — 질문이 다르다.
     */
    @Query("select count(c) from OrderClaim c where c.status not in :closed and c.receivedAt >= :from")
    long countOpenForAlerts(@Param("closed") Collection<ClaimStatus> closed,
                            @Param("from") LocalDateTime from);
}
