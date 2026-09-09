package com.pms.repository;

import com.pms.domain.PurchaseRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface PurchaseRecordRepository extends JpaRepository<PurchaseRecord, Long> {

    /**
     * 물품 묶음 단위로 구매 이력 일괄 조회 (그룹 집계용, PLAN 2609_29 D6).
     *
     * <p>🔴 판매자로 쪼개지 않는다 — 그룹의 구매수량은 전체 기준이다.
     */
    List<PurchaseRecord> findByProduct_IdIn(Collection<Long> productIds);

    /**
     * 그 물품의 최근 구매이력 (PLAN 2609_29 D9). <b>판매자 조건이 없다</b> — 물품 기준 지연 조회다.
     *
     * <p>{@code join fetch r.seller}: 각 줄에 판매자명을 붙여야 하는데(누구 것인지 보이지 않으면 못 읽는다)
     * LAZY 프록시를 줄마다 깨우면 N+1 이 된다. ToOne fetch 라 limit 는 SQL 에서 적용된다.
     */
    @Query("""
            select r from PurchaseRecord r join fetch r.seller
            where r.product.id = :productId
            order by r.purchasedOn desc, r.id desc
            """)
    List<PurchaseRecord> findRecentByProduct(@Param("productId") Long productId, Pageable pageable);

    /**
     * 원가 스냅샷용 — 그 (물품 × 판매자) 의 <b>주문일 이전</b> 최근 매입 (FEATURE_2609_28 / PLAN D20).
     *
     * <p>🔴 판매자를 조건에 넣는다. 재고는 공용이 아니고(2609_29 D4) 나간 물건은 그 판매자가 자기 돈으로
     * 들인 물건이다 — 남의 매입가를 빌려 쓰면 판매자별 손익이 서로 오염된다. 이력이 없으면
     * {@link com.pms.domain.CostBasis#LISTED} 로 내려가는 것이 맞다.
     *
     * <p>⚠️ {@code unitPrice is not null} 은 DB 가 거른다. 금액 미상 매입을 {@code 0} 으로 치면 원가가
     * 0 이 되어 손익이 통째로 망가지고, 목록에 남겨 두면 정렬 1위를 차지해 그 아래 멀쩡한 단가를 가린다.
     *
     * <p>⚠️ 정정(음수 수량) 행도 대상이다 — {@code PurchaseRecord.of} 가 음수 총액 ÷ 음수 수량으로
     * 단가를 <b>양수로</b> 유지하므로 정정 행의 단가도 그대로 쓸 수 있는 값이다.
     *
     * @param asOf 주문일. 주문 이후에 들어온 매입은 이 주문의 원가 근거가 아니다
     */
    @Query("""
            select r from PurchaseRecord r
            where r.product.id = :productId
              and r.seller.id = :sellerId
              and r.unitPrice is not null
              and r.purchasedOn <= :asOf
            order by r.purchasedOn desc, r.id desc
            """)
    List<PurchaseRecord> findLatestPriced(@Param("productId") Long productId,
                                          @Param("sellerId") Long sellerId,
                                          @Param("asOf") LocalDate asOf,
                                          Pageable pageable);
}
