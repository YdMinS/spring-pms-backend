package com.pms.repository;

import com.pms.domain.EsmAccountCredential;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ESM credential repository (FEATURE_2610_26 / D4). Read path = {@code MarketplaceAccount.esmCredential} (EAGER);
 * this repository only creates and updates the row.
 */
public interface EsmAccountCredentialRepository extends JpaRepository<EsmAccountCredential, Long> {
}
