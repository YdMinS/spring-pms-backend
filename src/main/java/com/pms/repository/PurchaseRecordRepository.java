package com.pms.repository;

import com.pms.domain.PurchaseRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
