package com.pms.repository;

import com.pms.domain.LotteonClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The LotteON side of claims (FEATURE_2611_23 / D59 · D61). Tenant-filtered by {@code @TenantId}. */
public interface LotteonClaimRepository extends JpaRepository<LotteonClaim, Long> {

    /** The row of one claim — the action decision reads it once per claim (R37). */
    Optional<LotteonClaim> findByOrderClaim_Id(Long orderClaimId);

    /** Rows of these claims in one query (status notes · action siblings · hold marks). */
    List<LotteonClaim> findByOrderClaim_IdIn(Collection<Long> orderClaimIds);
}
