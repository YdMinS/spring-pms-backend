package com.pms.repository;

import com.pms.domain.LotteonShippingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** LotteON channel shipping settings (FEATURE_2610_24 / D42). Lookup only through the account. */
public interface LotteonShippingConfigRepository extends JpaRepository<LotteonShippingConfig, Long> {

    Optional<LotteonShippingConfig> findByMarketplaceAccountId(Long marketplaceAccountId);
}
