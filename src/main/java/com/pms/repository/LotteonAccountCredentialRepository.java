package com.pms.repository;

import com.pms.domain.LotteonAccountCredential;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LotteON credential repository (FEATURE_2610_24 / D4). Read path = {@code MarketplaceAccount.lotteonCredential}
 * (EAGER); this repository only creates and updates the row.
 */
public interface LotteonAccountCredentialRepository extends JpaRepository<LotteonAccountCredential, Long> {
}
