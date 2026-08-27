package com.pms.repository;

import com.pms.domain.MarketplaceShippingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link MarketplaceShippingConfig} (FEATURE_2608_06 / 72). Account-scoped finder only —
 * isolation flows through the parent {@code MarketplaceAccount} (no tenant-less {@code findAll} of configs).
 */
public interface MarketplaceShippingConfigRepository extends JpaRepository<MarketplaceShippingConfig, Long> {

    Optional<MarketplaceShippingConfig> findByMarketplaceAccountId(Long marketplaceAccountId);
}
