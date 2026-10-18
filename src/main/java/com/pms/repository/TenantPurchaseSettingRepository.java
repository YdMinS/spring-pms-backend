package com.pms.repository;

import com.pms.domain.TenantPurchaseSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Tenant purchase settings (FEATURE_2610_09 / D72). {@code @TenantId} leaves only the current tenant's row. */
public interface TenantPurchaseSettingRepository extends JpaRepository<TenantPurchaseSetting, Long> {

    Optional<TenantPurchaseSetting> findFirstByOrderByIdAsc();
}
