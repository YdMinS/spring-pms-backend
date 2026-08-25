package com.pms.repository;

import com.pms.domain.PlatformCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link PlatformCategory} (FEATURE_2608_06 / 52).
 *
 * <p>Tenant-scoped via {@code @TenantId} on the entity — no manual tenant conditions needed. The
 * {@code findByPlatformAndParentAndName} finder is declared here for the 53 import's intermediate-node
 * upsert (not consumed in 52).</p>
 */
public interface PlatformCategoryRepository extends JpaRepository<PlatformCategory, Long> {

    Optional<PlatformCategory> findByPlatformAndCode(String platform, String code);

    List<PlatformCategory> findByParentIsNullAndPlatform(String platform);

    List<PlatformCategory> findByParentId(Long parentId);

    /** Intermediate-node upsert key for the 53 import (declared here, consumed there). */
    Optional<PlatformCategory> findByPlatformAndParentAndName(String platform, PlatformCategory parent, String name);
}
