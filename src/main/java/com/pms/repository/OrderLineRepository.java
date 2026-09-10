package com.pms.repository;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.dto.response.CostBasisBreakdown;
import com.pms.dto.response.MonthlyChannelSales;
import com.pms.dto.response.SalesLineGroup;
import com.pms.dto.response.SalesLineView;
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

    // ── 출고 확인 (FEATURE_2609_28 / PLAN D11) ─────────────────────────────

    /**
     * 아직 안 나간 주문 라인 — 출고 대상 목록.
     *
     * <p>🔴 {@code status} 는 {@code PAID}/{@code PREPARING} 만 넘어온다(종결 상태는 작업 대상이 아니다).
     * 전량 취소는 저장되는 상태가 아니라 파생값이므로({@code effectiveStatus()}) 서비스가 한 번 더 거른다.
     *
     * <p>⚠️ 판매자 필터는 {@code order → marketplaceAccount → seller} 로 해석한다(PLAN 2609_29 D4) —
     * 재고가 판매자별로 갈리므로 출고 화면도 같은 축을 가진다.
     *
     * <p>⚠️ {@code @EntityGraph} 로 옵션까지 끌고 온다: {@code open-in-view=false} 라 BOM 전개가
     * {@code productListingOption → masterProductOption} 을 라인마다 지연로딩하면 N+1 이 된다.
     */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount", "order.marketplaceAccount.seller",
            "productListingOption", "productListingOption.masterProductOption"})
    @Query("""
            SELECT l FROM OrderLine l
             WHERE l.status IN :statuses
               AND (:sellerId IS NULL OR l.order.marketplaceAccount.seller.id = :sellerId)
             ORDER BY l.order.orderedAt ASC, l.id ASC
            """)
    List<OrderLine> findOutboundTargets(@Param("statuses") Collection<OrderStatus> statuses,
                                        @Param("sellerId") Long sellerId);

    /** 출고 확인 대상 단건 — 전개(옵션·마스터 옵션)와 판매자 유도에 필요한 것을 전부 즉시 로딩한다. */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount", "order.marketplaceAccount.seller",
            "productListingOption", "productListingOption.masterProductOption"})
    Optional<OrderLine> findWithListingOptionById(Long id);

    /**
     * id 목록으로 주문 라인 조회 — 발주처리·주문취소 전개용.
     *
     * <p>seller 는 쓰지 않으므로 계정까지만 즉시 로딩한다(2609_17 D1).
     */
    @EntityGraph(attributePaths = {"order", "order.marketplaceAccount", "orderShipment"})
    List<OrderLine> findWithAccountByIdIn(List<Long> ids);

    // ── 정산 라인 매핑 (FEATURE_2609_30 / PLAN D7) ──────────────────────────

    /**
     * 주문번호 + 채널 옵션으로 주문 라인 조회 — 정산 라인 매핑의 도착지.
     *
     * <p>매칭 실패(0건)는 결함이 아니라 {@code UNMATCHED} 라는 정상 상태다 — 광고비 상계·기간 밖 주문은
     * 애초에 매칭될 수 없다. 2건 이상(합포장 분할)이면 호출자가 매핑을 포기한다: 틀린 라인에 붙이면
     * 상품별 수익성이 조용히 오염되므로, 모르는 채로 두는 편이 낫다.
     *
     * <p>⚠️ {@code product_listing_option_id} 는 2609_28(079)이 백필한 컬럼이라 비어 있는 라인이 있을 수
     * 있다 — 그 경우 이 조회는 0건이 되고 정산 라인은 UNMATCHED 로 남는다(PLAN "남는 위험").
     */
    @Query("""
            SELECT l FROM OrderLine l
             WHERE l.order.externalOrderId = :externalOrderId
               AND l.productListingOption.id = :productListingOptionId
            """)
    List<OrderLine> findByExternalOrderIdAndListingOptionId(
            @Param("externalOrderId") String externalOrderId,
            @Param("productListingOptionId") Long productListingOptionId);

    /**
     * 기간별 원가 근거 구성비 (FEATURE_2609_28 / PLAN D20) — 예: {@code 최근매입가 70% · 기준가 30%}.
     *
     * <p>🔴 <b>이 숫자가 FIFO 투자 여부를 결정한다.</b> 추정 등급 비중이 낮으면 FIFO 를 만들 이유가 없다.
     *
     * <p>⚠️ 기간 축은 주문일이 아니라 {@code costSnapshotAt}(구운 시각)이다 — 스냅샷이 없는 라인은
     * 애초에 나가지 않은 라인이라 집계 대상이 아니고, 이 조건이 그것을 자동으로 걸러 준다.
     *
     * <p>⚠️ 생성자 projection 이다. {@code select l.costBasis, count(l), ...} 로 두면 {@code Object[]} 가
     * 돌아와 record 로 받히지 않는다. {@code count(l)} 이 {@code Long} 이므로 record 필드도 {@code long}.
     */
    @Query("""
            select new com.pms.dto.response.CostBasisBreakdown(
                l.costBasis, count(l), coalesce(sum(l.costAmount), 0))
            from OrderLine l
            where l.costSnapshotAt between :from and :to
            group by l.costBasis
            """)
    List<CostBasisBreakdown> findCostBasisBreakdown(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    // ── 매출 집계 (FEATURE_2609_30 / PLAN D14 · 03) ─────────────────────────

    /**
     * 기간 매출을 <b>계정 × 채널 옵션</b> 단위로 접어서 돌려준다 — 매출 API 3개가 공유하는 유일한 집계 경로.
     *
     * <p>🔴 <b>라인을 자바로 가져와 더하지 않는다.</b> 주문량은 계속 늘지만 팔린 옵션 수는 그렇지 않다.
     * 판매자/채널/상품 축은 전부 이 결과를 메모리에서 다시 접어 만든다(그룹 키만 바뀐다).
     *
     * <p>정의(PLAN D14 · 03 Step 1):
     * <pre>
     *   netQty  = orderQty − cancelQty        🔴 holdQty(환불대기)는 <b>빼지 않는다</b>
     *   gross   = Σ (unitPrice × netQty)      할인 <b>전</b>
     *   discount= Σ (discountAmount × netQty / orderQty)   유효수량 비례 안분
     * </pre>
     *
     * <p>⚠️ {@code left join} 이 세 개인 이유: 채널 옵션이 없는 라인(백필 누락·WING 수정분)을 <b>버리지 않기</b>
     * 위해서다. inner join 으로 바꾸면 이 목록의 합계가 판매자 요약과 조용히 어긋난다.
     *
     * <p>⚠️ {@code nullif(l.orderQty, 0)} 는 0으로 나누는 것을 막는다 — 안분의 분모가 데이터에 달려 있다.
     * {@code missingCostLines} 는 <b>유효수량이 남은</b> 라인만 센다: 전량 취소된 라인은 애초에 나가지 않아
     * 원가 스냅샷이 없는 것이 정상인데, 그것까지 세면 취소 한 건에 순이익 전체가 {@code null} 이 된다.
     *
     * @param toExclusive 상한 <b>배타</b>. 종료일의 23:59:59 를 만들지 않기 위해 다음 날 00:00 을 넘긴다
     */
    @Query("""
            select new com.pms.dto.response.SalesLineGroup(
                s.id, s.sellerName, a.id, plo.id, mp.id, mp.name,
                sum(l.orderQty - l.cancelQty),
                sum(l.holdQty),
                sum(coalesce(l.unitPrice, 0) * (l.orderQty - l.cancelQty)),
                sum(coalesce(l.discountAmount, 0) * (l.orderQty - l.cancelQty)
                        / coalesce(nullif(l.orderQty, 0), 1)),
                sum(coalesce(l.costAmount, 0) * (l.orderQty - l.cancelQty)
                        / coalesce(nullif(l.orderQty, 0), 1)),
                sum(case when l.costAmount is null and l.orderQty > l.cancelQty then 1 else 0 end))
            from OrderLine l
              join l.order o
              join o.marketplaceAccount a
              join a.seller s
              left join l.productListingOption plo
              left join plo.masterProductOption mpo
              left join mpo.masterProduct mp
            where o.orderedAt >= :from and o.orderedAt < :toExclusive
              and (:sellerId is null or s.id = :sellerId)
            group by s.id, s.sellerName, a.id, plo.id, mp.id, mp.name
            """)
    List<SalesLineGroup> aggregateSales(@Param("from") LocalDateTime from,
                                        @Param("toExclusive") LocalDateTime toExclusive,
                                        @Param("sellerId") Long sellerId);

    /**
     * 판매 내역 — 집계하지 않은 <b>주문 라인 목록</b> (FEATURE_2609_34).
     *
     * <p>채널별 매출 화면이 "이 매출이 어디서 나왔나"를 보여주는 목록이다. {@link #aggregateSales} 가
     * 같은 라인을 접어서 합계를 내는 반면, 이쪽은 접지 않고 주문번호와 함께 그대로 내려준다.
     *
     * <p>🔴 <b>금액식을 {@link #aggregateSales} 에서 그대로 옮겨 온다</b> — 유효수량·비례 안분·좌변 우변이
     * 하나라도 다르면 이 목록의 합이 화면 위쪽 채널 합계와 어긋난다. 둘 중 어느 쪽이 맞는지는 화면을 보는
     * 사람이 알 수 없으므로, 어긋나는 순간 두 숫자를 다 못 쓰게 된다.
     *
     * <p>⚠️ {@code left join} 세 개는 여기서도 그대로다 — 채널 옵션이 없는 라인(백필 누락·WING 수정분)을
     * <b>버리지 않기</b> 위해서다. inner join 으로 바꾸면 그 라인만 조용히 사라져 합계가 안 맞는다.
     *
     * <p>⚠️ 계정은 <b>필수</b>다. 이 목록은 한 채널을 들여다보는 화면 전용이라, 계정 없이 부르면 전 채널
     * 라인이 통째로 나와 화면이 감당할 수 없다.
     *
     * @param toExclusive 상한 <b>배타</b>. 종료일의 23:59:59 를 만들지 않기 위해 다음 날 00:00 을 넘긴다
     */
    @Query("""
            select new com.pms.dto.response.SalesLineView(
                l.id, a.id, o.orderedAt, o.externalOrderId, l.itemName, mp.name,
                l.orderQty, l.cancelQty, l.holdQty, l.orderQty - l.cancelQty,
                coalesce(l.unitPrice, 0),
                coalesce(l.unitPrice, 0) * (l.orderQty - l.cancelQty),
                coalesce(l.discountAmount, 0) * (l.orderQty - l.cancelQty)
                        / coalesce(nullif(l.orderQty, 0), 1))
            from OrderLine l
              join l.order o
              join o.marketplaceAccount a
              left join l.productListingOption plo
              left join plo.masterProductOption mpo
              left join mpo.masterProduct mp
            where a.id = :accountId
              and o.orderedAt >= :from and o.orderedAt < :toExclusive
            order by o.orderedAt desc, l.id desc
            """)
    List<SalesLineView> findSalesLines(@Param("from") LocalDateTime from,
                                       @Param("toExclusive") LocalDateTime toExclusive,
                                       @Param("accountId") Long accountId);

    // ── 고정비 부과 판정용 월별 집계 (FEATURE_2609_33 / PLAN 2609_33 D4-1 · D11 · D11-1) ──────

    /**
     * 계정 × <b>달</b> 상품매출(할인 후). 고정비 부과 판정의 유일한 근거다.
     *
     * <p>🔴 금액식은 {@link #aggregateSales} 의 {@code grossSales − discount} 를 <b>그대로</b> 옮긴 것이다
     * (D11): {@code Σ(unitPrice × netQty) − Σ(discountAmount × netQty / orderQty)}. 다른 식을 새로 쓰면
     * 화면 매출과 판정 근거가 어긋나 사용자가 둘 중 하나를 버그로 읽는다. 🔴 {@code grossSales} 단독
     * (할인 <b>전</b>)으로 판정하면 쿠폰이 걸린 채널이 부과되지 않은 달을 부과로 판정한다.
     *
     * <p>🔴 날짜 축은 {@code o.orderedAt} — 화면 집계와 같은 축이다(D11-1). 플랫폼의 인식 시점과 같다고
     * 단정하지 않는다. 어긋남은 {@code chargeMode} 로 사람이 덮는다(D2).
     *
     * <p>⚠️ 월 그룹핑은 {@code year()}/{@code month()} 다. {@code date_format} 같은 네이티브 함수는
     * H2(테스트)와 MySQL(운영)에서 다르게 동작한다 — {@code YYYY-MM} 문자열 조립은 서비스가 한다.
     *
     * <p>⚠️ 채널 옵션 join 이 없다: 판정에 필요한 것은 계정 × 달 금액뿐이라 {@code left join} 3개를
     * 다시 태울 이유가 없다(그래도 라인이 버려지지 않는다는 성질은 동일하다).
     *
     * @param from        걸친 달들의 <b>1일 00:00</b>(조회 시작일이 아니다, D4-1)
     * @param toExclusive 마지막 달 <b>다음 달 1일 00:00</b> — 배타 상한
     */
    @Query("""
            select new com.pms.dto.response.MonthlyChannelSales(
                a.id, year(o.orderedAt), month(o.orderedAt),
                sum(coalesce(l.unitPrice, 0) * (l.orderQty - l.cancelQty))
                    - sum(coalesce(l.discountAmount, 0) * (l.orderQty - l.cancelQty)
                            / coalesce(nullif(l.orderQty, 0), 1)))
            from OrderLine l
              join l.order o
              join o.marketplaceAccount a
              join a.seller s
            where o.orderedAt >= :from and o.orderedAt < :toExclusive
              and (:sellerId is null or s.id = :sellerId)
            group by a.id, year(o.orderedAt), month(o.orderedAt)
            """)
    List<MonthlyChannelSales> aggregateMonthlySales(@Param("from") LocalDateTime from,
                                                    @Param("toExclusive") LocalDateTime toExclusive,
                                                    @Param("sellerId") Long sellerId);
}
