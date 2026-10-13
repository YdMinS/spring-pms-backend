package com.pms.repository;

import com.pms.domain.MasterPlatformCategory;
import com.pms.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MasterPlatformCategory} — a master's own platform category (FEATURE_2610_12 / D40).
 *
 * <p>No {@code @TenantId}; isolation flows through the master. Only master-scoped finders are exposed — the
 * caller resolves the master through the tenant-scoped {@code MasterProductRepository.findScopedById} first.
 * The inherited tenant-less {@code findAll} must not be used for lookups.</p>
 */
public interface MasterPlatformCategoryRepository extends JpaRepository<MasterPlatformCategory, Long> {

    List<MasterPlatformCategory> findByMasterProductId(Long masterProductId);

    Optional<MasterPlatformCategory> findByMasterProductIdAndPlatform(Long masterProductId, Platform platform);

    /** D41: an own row counts as "the platform has a category" next to the standard category mapping. */
    boolean existsByMasterProductIdAndPlatform(Long masterProductId, Platform platform);

    /** D42 (standard category changed) · master delete — remove every own row of the master. */
    @Modifying
    @Transactional
    void deleteByMasterProductId(Long masterProductId);
}
