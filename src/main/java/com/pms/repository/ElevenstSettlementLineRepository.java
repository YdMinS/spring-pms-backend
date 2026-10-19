package com.pms.repository;

import com.pms.domain.ElevenstSettlementLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 11st settlement line mirror (FEATURE_2610_20 / D3). Tenant-filtered by {@code @TenantId}. */
public interface ElevenstSettlementLineRepository extends JpaRepository<ElevenstSettlementLine, Long> {

    /** The mirror row of one neutral line — the idempotency key of the mirror write. */
    Optional<ElevenstSettlementLine> findBySettlementLine_Id(Long settlementLineId);

    /** Every mirror row of one account with its neutral line — the input of the remittance-date grouping (D29). */
    @EntityGraph(attributePaths = {"settlementLine", "settlementLine.settlementPayout"})
    List<ElevenstSettlementLine> findByMarketplaceAccount_Id(Long marketplaceAccountId);
}
