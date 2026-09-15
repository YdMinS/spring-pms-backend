package com.pms.repository;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
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
 * customer_inquiry 접근 (FEATURE_2609_23).
 *
 * ⚠️ 응답에 실리는 조회는 {@code @EntityGraph} 로 계정·셀러를 즉시 로딩한다 — open-in-view=false 라
 * 매핑 시점에 지연로딩이면 LazyInitializationException 이 난다. 반대로 동기화가 쓰는 조회는 붙이지
 * 않는다(응답에 싣지 않으므로 연관을 끌 이유가 없다).
 * ⚠️ {@code @TenantId} 가 SELECT 를 자동 필터하므로 쿼리에 tenant 조건을 직접 넣지 않는다.
 * ⚠️ {@code replies} 를 fetch 하지 않는다 — 상세만 스레드를 그리고, 그때도 별도 조회로 읽는다(PLAN §3).
 */
public interface CustomerInquiryRepository extends JpaRepository<CustomerInquiry, Long> {

    /**
     * UNIQUE 3키로 기존 문의 조회 (동기화 upsert 의 멱등성 키).
     *
     * ⚠️ {@code inquiryType} 이 키에 들어간다 — 상품문의 {@code inquiryId} 와 고객센터 {@code inquiryId}
     * 는 다른 시퀀스라, 빼면 값이 겹칠 때 한쪽이 다른 쪽 행을 덮어쓴다.
     */
    Optional<CustomerInquiry> findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
            Long accountId, InquiryType inquiryType, String externalInquiryId);

    /**
     * 계정의 가장 오래된 특정 상태 문의 1건 — 조회 앵커 계산(D8)의 원천.
     *
     * INDEX(marketplace_account_id, status, inquired_at) 가 정확히 이 조회를 위한 것이다.
     * 유형을 받지 않는다: 앵커는 계정 단위이고 두 유형이 같은 슬라이스를 공유한다.
     */
    Optional<CustomerInquiry> findTopByMarketplaceAccount_IdAndStatusOrderByInquiredAtAsc(
            Long accountId, InquiryStatus status);

    /**
     * STALE 스윕 대상 (D9) — 컷오프보다 오래된 미답변 건.
     *
     * ⚠️ 벌크 {@code @Modifying} UPDATE 를 쓰지 않는다 — {@code @TenantId} 필터가 JPQL 벌크 갱신에
     * 적용되는지 확신할 수 없다({@code ClaimStaleSweeper} 와 같은 판단). 건수가 작으므로 로드 후 개별 저장.
     */
    List<CustomerInquiry> findByMarketplaceAccount_IdAndStatusAndInquiredAtBefore(
            Long accountId, InquiryStatus status, LocalDateTime cutoff);

    /**
     * 목록 조회 — GET /api/inquiries. 필터 6개를 <b>nullable 파라미터 쿼리 1개</b>로 조립한다.
     *
     * ⚠️ 조합별 명명 메서드({@code findInPeriodBySellerAndAccountAndType}…)를 만들지 말 것 —
     * 필터가 6개라 조합이 폭발한다. {@code (:param is null or ...)} 패턴 한 개로 조립한다
     * (저장소에 {@code JpaSpecificationExecutor} 선례가 없다).
     *
     * @param start 문의일 하한(포함)
     * @param end   문의일 상한(<b>배타적</b>) — 당일 마지막 초에 들어온 문의를 놓치지 않기 위함
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderLine", "productListing"})
    @Query("select i from CustomerInquiry i "
            + "where (:type is null or i.inquiryType = :type) "
            + "and (:status is null or i.status = :status) "
            + "and (:accountId is null or i.marketplaceAccount.id = :accountId) "
            + "and (:sellerId is null or i.marketplaceAccount.seller.id = :sellerId) "
            + "and i.inquiredAt >= :start and i.inquiredAt < :end "
            + "and (:keyword is null "
            + "     or lower(i.content) like lower(concat('%', :keyword, '%')) "
            + "     or lower(i.itemName) like lower(concat('%', :keyword, '%')) "
            + "     or lower(i.externalOrderId) like lower(concat('%', :keyword, '%'))) "
            + "order by i.inquiredAt desc")
    List<CustomerInquiry> search(@Param("type") InquiryType type,
                                 @Param("status") InquiryStatus status,
                                 @Param("accountId") Long accountId,
                                 @Param("sellerId") Long sellerId,
                                 @Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end,
                                 @Param("keyword") String keyword);

    /** 단건 상세 — GET /api/inquiries/{id}. 스레드는 별도 조회다(컬렉션 fetch 금지). */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller", "orderLine", "productListing"})
    Optional<CustomerInquiry> findWithAccountById(Long id);

    /**
     * 미답변 문의 건수 — 알림 배지(FEATURE_2609_49 / D9·D14).
     *
     * <p>🔴 클레임 배지와 같은 자세로 <b>유형 무관·기간 무관</b>이다 — 목록 화면의 기본 필터와 다른 것이
     * 정상이며, 실질 상한은 inquiry-stale-days(30) 스윕이 만든다.
     *
     * <p>호출부는 {@link InquiryStatus#openStatuses()} 를 넘긴다. {@code countByStatus(UNANSWERED)} 로
     * 값을 박지 않는다 — 판정은 {@link InquiryStatus#isOpen()} 이 이미 소유하고 있고, 상태가 늘 때
     * 한쪽만 갈리는 것을 막는다.
     * ⚠️ {@code @TenantId} 가 자동 적용된다 — 테넌트 조건을 손으로 붙이지 않는다.
     */
    long countByStatusIn(Collection<InquiryStatus> statuses);

    /**
     * 미답변 문의 한 장 — 알림 목록(FEATURE_2609_51 / D8·D12). 기간·커서·건수 상한이 모두 걸린다.
     *
     * <p>🔴 <b>동률을 쿼리에서 자른다</b>: {@code (t < :cursorTime or (t = :cursorTime and id < :cursorTieId))}.
     * "{@code <=} 로 넉넉히 읽고 서비스가 여유분(+N)으로 거르는" 방식을 쓰지 말 것 — 같은 시각 건이
     * 여유분보다 많으면 그 행들이 <b>조용히 사라진다.</b> {@code cursorTieId} 는 서비스가 소스별로 준다.
     *
     * <p>⚠️ {@code Pageable} 로 정확히 페이지 크기만 읽는다 — <b>기간 안 전부를 읽지 않는다.</b>
     * ⚠️ 응답이 {@code seller.getSellerName()} 을 읽으므로 {@code @EntityGraph} 가 필요하다
     * (open-in-view=false). 목록은 {@code replies} 를 fetch 하지 않는다.
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    @Query("select i from CustomerInquiry i where i.status in :open and i.inquiredAt >= :from "
            + "and (i.inquiredAt < :cursorTime or (i.inquiredAt = :cursorTime and i.id < :cursorTieId)) "
            + "order by i.inquiredAt desc, i.id desc")
    List<CustomerInquiry> findOpenForAlerts(@Param("open") Collection<InquiryStatus> open,
                                            @Param("from") LocalDateTime from,
                                            @Param("cursorTime") LocalDateTime cursorTime,
                                            @Param("cursorTieId") Long cursorTieId,
                                            Pageable pageable);

    /**
     * 종 배지용 미답변 문의 건수 (FEATURE_2609_51 / D3).
     *
     * <p>🔴 목록과 <b>같은 기간 조건</b>이어야 한다 — 한쪽만 기간을 걸면 배지와 목록 건수가 어긋난다.
     * 🔴 사이드바 배지용 {@link #countByStatusIn}(기간 무관)은 <b>건드리지 말 것</b> — 질문이 다르다.
     */
    @Query("select count(i) from CustomerInquiry i where i.status in :open and i.inquiredAt >= :from")
    long countOpenForAlerts(@Param("open") Collection<InquiryStatus> open,
                            @Param("from") LocalDateTime from);
}
