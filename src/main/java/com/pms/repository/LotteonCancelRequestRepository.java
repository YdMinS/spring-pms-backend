package com.pms.repository;

import com.pms.domain.LotteonCancelRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** LotteON cancel request lines (FEATURE_2611_23 / D24 · R22). Tenant-filtered by {@code @TenantId}. */
public interface LotteonCancelRequestRepository extends JpaRepository<LotteonCancelRequest, Long> {

    /** Natural key lookup — the idempotency key of the [50] load. */
    Optional<LotteonCancelRequest> findByMarketplaceAccount_IdAndClmNoAndOdSeq(Long accountId, String clmNo,
                                                                               String odSeq);

    /** Every request of one LotteON order line — the source of its cancel quantity (R22). */
    List<LotteonCancelRequest> findByMarketplaceAccount_IdAndOdNoAndOdSeq(Long accountId, String odNo, String odSeq);

    /** Requests of one order line with the given status (order detail 「취소 요청」). */
    List<LotteonCancelRequest> findByOrderLine_IdAndPlatformStatus(Long orderLineId, String platformStatus);

    /** One request with its account (approve / reject run outside a transaction). */
    @EntityGraph(attributePaths = {"marketplaceAccount"})
    Optional<LotteonCancelRequest> findWithAccountById(Long id);
}
