package com.pms.repository;

import com.pms.domain.TenantOrderSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 테넌트 주문관리 설정 (FEATURE_2609_75 / D12). {@code @TenantId} 필터로 현재 테넌트의 1행만 보인다. */
public interface TenantOrderSettingRepository extends JpaRepository<TenantOrderSetting, Long> {

    Optional<TenantOrderSetting> findFirstByOrderByIdAsc();
}
