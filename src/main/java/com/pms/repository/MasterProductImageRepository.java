package com.pms.repository;

import com.pms.domain.MasterProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link MasterProductImage} (FEATURE_2608_06 / Step 2-1).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent {@link com.pms.domain.MasterProduct}
 * (callers resolve the tenant-scoped master first via {@code MasterProductRepository.findScopedById}). Only
 * master-scoped finders are exposed; a tenant-less {@code findAll} must NOT be used to read images.</p>
 */
public interface MasterProductImageRepository extends JpaRepository<MasterProductImage, Long> {

    /** All images of a master, ordered by zone then position (list view). */
    List<MasterProductImage> findByMasterProductIdOrderByZoneIdAscSortOrderAsc(Long masterProductId);

    /** Images of a single zone in render order. */
    List<MasterProductImage> findByMasterProductIdAndZoneIdOrderBySortOrderAsc(Long masterProductId, String zoneId);

    /** Images of a single zone (unordered) — next-sortOrder computation + reorder validation. */
    List<MasterProductImage> findByMasterProductIdAndZoneId(Long masterProductId, String zoneId);
}
