package com.pms.repository;

import com.pms.domain.MasterProductComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link MasterProductComponent} (FEATURE_2608_06 / 3b-1).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent master, so only
 * master-scoped finders are exposed (no tenant-less {@code findAll} usage). Component sets are updated
 * by {@link #deleteByMasterProductId} + re-insert.</p>
 */
public interface MasterProductComponentRepository extends JpaRepository<MasterProductComponent, Long> {

    List<MasterProductComponent> findByMasterProductId(Long masterProductId);

    void deleteByMasterProductId(Long masterProductId);
}
