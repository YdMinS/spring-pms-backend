package com.pms.repository;

import com.pms.domain.ElevenstShippingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 11st channel shipping settings (FEATURE_2610_13 / D84). Lookup only through the account. */
public interface ElevenstShippingConfigRepository extends JpaRepository<ElevenstShippingConfig, Long> {

    Optional<ElevenstShippingConfig> findByMarketplaceAccountId(Long marketplaceAccountId);
}
