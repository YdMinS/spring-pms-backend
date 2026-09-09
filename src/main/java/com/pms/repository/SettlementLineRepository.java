package com.pms.repository;

import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 정산 라인 리포지토리 (FEATURE_2609_30 / PLAN D10).
 *
 * <p>🔴 적재는 <b>멱등 upsert</b> 다 — 조회 finder 는 라인의 유일키 그대로여야 한다. 키에
 * {@code settlement_payout_id} 를 넣지 말 것: 01 시점엔 항상 null 이라 어떤 행도 못 찾고, 정정
 * 재조회가 전부 중복 INSERT 로 떨어진다.
 */
public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {

    /**
     * 유일키 단건 조회 — upsert 의 진입점.
     *
     * <p>⚠️ {@code @EntityGraph} 로 {@code orderLine} 을 즉시 로딩한다: upsert 는 기존 매핑을 보존해야
     * 하는데(D10) 지연로딩이면 매핑 유무 판정에서 프록시를 건드리게 된다.
     */
    @EntityGraph(attributePaths = {"orderLine", "productListingOption", "settlementPayout"})
    Optional<SettlementLine> findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
            Long marketplaceAccountId, String externalOrderId, String platformOptionId,
            SaleType saleType, LocalDate recognitionDate);
}
