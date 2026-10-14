package com.pms.repository;

import com.pms.domain.ElevenstAccountCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 11st credential repository (FEATURE_2610_13 / D2). Read path = {@code MarketplaceAccount.elevenstCredential}
 * (EAGER); this repository only creates/updates the row and stamps the last key use (D88).
 */
public interface ElevenstAccountCredentialRepository extends JpaRepository<ElevenstAccountCredential, Long> {

    Optional<ElevenstAccountCredential> findByMarketplaceAccountId(Long marketplaceAccountId);

    /** D88: stamp the last successful keyed call. Native on purpose — one row, no entity load. */
    @Modifying
    @Query(value = "UPDATE elevenst_account_credential SET key_last_used_at = :usedAt "
            + "WHERE marketplace_account_id = :accountId", nativeQuery = true)
    int touchKeyUsedAt(@Param("accountId") Long accountId, @Param("usedAt") LocalDateTime usedAt);
}
