package com.pms.repository;

import com.pms.domain.CoupangShippingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link CoupangShippingConfig} (FEATURE_2608_06 / 72). Account-scoped finder only —
 * isolation comes from {@code @TenantId} on the entity (PLAN D25) plus the parent {@code MarketplaceAccount}
 * (no tenant-less {@code findAll} of configs).
 */
public interface CoupangShippingConfigRepository extends JpaRepository<CoupangShippingConfig, Long> {

    Optional<CoupangShippingConfig> findByMarketplaceAccountId(Long marketplaceAccountId);
}
