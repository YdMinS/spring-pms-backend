package com.pms.repository;

import com.pms.domain.TenantElevenstSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** The company's 11st photo lookup key (FEATURE_2611_11 / D39). {@code @TenantId} leaves only the current tenant's row. */
public interface TenantElevenstSettingRepository extends JpaRepository<TenantElevenstSetting, Long> {

    Optional<TenantElevenstSetting> findFirstByOrderByIdAsc();
}
