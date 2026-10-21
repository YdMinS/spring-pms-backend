package com.pms.repository;

import com.pms.domain.EsmShippingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** ESM channel shipping settings (FEATURE_2610_26 / D36). Lookup only through the account. */
public interface EsmShippingConfigRepository extends JpaRepository<EsmShippingConfig, Long> {

    Optional<EsmShippingConfig> findByMarketplaceAccountId(Long marketplaceAccountId);
}
