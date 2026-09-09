package com.pms.repository;

import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * 귀속 대상 라인 (FEATURE_2609_30 / 02 · PLAN D5-5): 인식일 구간 안에서 <b>아직 주인이 없거나</b>
     * 이미 이 묶음의 것인 라인.
     *
     * <p>🔴 이미 <b>다른</b> 묶음에 귀속된 라인은 제외한다 — 추가정산이 주정산과 같은 구간을 덮으므로
     * 양쪽이 같은 라인을 가져가면 매출이 두 번 계상된다. 호출자가 묶음을 {@code settlementDate ASC} 로
     * 처리해 결과를 결정적으로 만든다.
     */
    @Query("SELECT l FROM SettlementLine l WHERE l.marketplaceAccount.id = :accountId "
            + "AND l.recognitionDate BETWEEN :from AND :to "
            + "AND (l.settlementPayout IS NULL OR l.settlementPayout.id = :payoutId)")
    List<SettlementLine> findAttributable(@Param("accountId") Long accountId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to,
                                          @Param("payoutId") Long payoutId);

    /** 묶음에 귀속된 라인 — 대사·리포트·엑셀의 공통 입력. */
    @EntityGraph(attributePaths = {"orderLine", "productListingOption", "productListingOption.productListing"})
    List<SettlementLine> findBySettlementPayout_IdOrderByRecognitionDateAscIdAsc(Long settlementPayoutId);

    long countBySettlementPayout_Id(Long settlementPayoutId);
}
