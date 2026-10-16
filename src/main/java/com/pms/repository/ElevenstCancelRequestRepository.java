package com.pms.repository;

import com.pms.domain.ElevenstCancelRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 11st cancel claims (FEATURE_2610_17 / D40). Tenant-filtered by {@code @TenantId}. */
public interface ElevenstCancelRequestRepository extends JpaRepository<ElevenstCancelRequest, Long> {

    Optional<ElevenstCancelRequest> findByMarketplaceAccount_IdAndOrdPrdCnSeq(Long accountId, String ordPrdCnSeq);

    /** Every claim of one order line — the source of its hold / cancel quantities (D40). */
    List<ElevenstCancelRequest> findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(Long accountId, String ordNo,
                                                                                String ordPrdSeq);

    /** Claims of these order lines with the given status (order detail · D40). */
    List<ElevenstCancelRequest> findByOrderLine_IdInAndPlatformStatus(Collection<Long> orderLineIds,
                                                                      String platformStatus);

    /** One claim with its account and order line (approve / reject run outside a transaction). */
    @EntityGraph(attributePaths = {"marketplaceAccount", "orderLine", "orderLine.order"})
    Optional<ElevenstCancelRequest> findWithAccountById(Long id);
}
