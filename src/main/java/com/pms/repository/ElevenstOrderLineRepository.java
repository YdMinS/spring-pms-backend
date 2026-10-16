package com.pms.repository;

import com.pms.domain.ElevenstOrderLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 11st order line mirror (FEATURE_2610_17 / D5). Tenant-filtered by {@code @TenantId}. */
public interface ElevenstOrderLineRepository extends JpaRepository<ElevenstOrderLine, Long> {

    /** Natural key lookup — the idempotency key of the 11st order load. */
    @EntityGraph(attributePaths = {"orderLine"})
    Optional<ElevenstOrderLine> findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(Long accountId, String ordNo,
                                                                                String ordPrdSeq);

    /** Mirror rows of these order lines in one query (response mapping · purchase list · shipment). */
    List<ElevenstOrderLine> findByOrderLine_IdIn(Collection<Long> orderLineIds);

    Optional<ElevenstOrderLine> findByOrderLine_Id(Long orderLineId);
}
