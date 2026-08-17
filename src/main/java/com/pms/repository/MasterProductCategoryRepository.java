package com.pms.repository;

import com.pms.domain.MasterProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MasterProductCategory} (FEATURE_2608_06 / 13).
 *
 * <p>No {@code @TenantId} — isolation flows through the parent master, so only master-scoped finders are
 * exposed (no tenant-less {@code findAll}). The inherited PK {@code findById} is used only after the parent
 * master is tenant-scoped.</p>
 */
public interface MasterProductCategoryRepository extends JpaRepository<MasterProductCategory, Long> {

    List<MasterProductCategory> findByMasterProductId(Long masterProductId);

    Optional<MasterProductCategory> findByMasterProductIdAndPlatform(Long masterProductId, String platform);
}
