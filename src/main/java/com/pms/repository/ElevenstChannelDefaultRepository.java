package com.pms.repository;

import com.pms.domain.ElevenstChannelDefault;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 11st channel notice defaults (FEATURE_2610_16 / D6). Lookup only through the account. */
public interface ElevenstChannelDefaultRepository extends JpaRepository<ElevenstChannelDefault, Long> {

    Optional<ElevenstChannelDefault> findByMarketplaceAccountId(Long marketplaceAccountId);
}
