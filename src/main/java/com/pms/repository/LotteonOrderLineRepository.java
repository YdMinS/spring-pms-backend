package com.pms.repository;

import com.pms.domain.LotteonOrderLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** LotteON order line mirror (FEATURE_2611_23 / D6). Tenant-filtered by {@code @TenantId}. */
public interface LotteonOrderLineRepository extends JpaRepository<LotteonOrderLine, Long> {

    /** Natural key lookup — the idempotency key of the LotteON order load. */
    @EntityGraph(attributePaths = {"orderLine"})
    Optional<LotteonOrderLine> findByMarketplaceAccount_IdAndOdNoAndOdSeq(Long accountId, String odNo, String odSeq);

    /** Every mirror row of one order — the inquiry order-line link (FEATURE_2611_23 / R43). */
    @EntityGraph(attributePaths = {"orderLine"})
    List<LotteonOrderLine> findByMarketplaceAccount_IdAndOdNo(Long accountId, String odNo);

    /** Mirror rows of these order lines in one query (response mapping · purchase list · order confirmation). */
    List<LotteonOrderLine> findByOrderLine_IdIn(Collection<Long> orderLineIds);
}
