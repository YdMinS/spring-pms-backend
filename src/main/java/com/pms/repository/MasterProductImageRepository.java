package com.pms.repository;

import com.pms.domain.MasterProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link MasterProductImage} — the master's image pool (FEATURE_2608_06 / Step 2-1 → 37).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent {@link com.pms.domain.MasterProduct}
 * (callers resolve the tenant-scoped master first via {@code MasterProductRepository.findScopedById}). Only
 * master-scoped finders are exposed; a tenant-less {@code findAll} must NOT be used to read images.</p>
 *
 * <p>⚠️ Zone-based finders were removed in 37 (upload no longer equals a zone binding — see
 * {@link com.pms.domain.MasterImageZoneAssignment}). Zone membership is queried through
 * {@code MasterImageZoneAssignmentRepository}.</p>
 */
public interface MasterProductImageRepository extends JpaRepository<MasterProductImage, Long> {

    /** All pool images of a master in pool order (list view + next-sortOrder computation). */
    List<MasterProductImage> findByMasterProductIdOrderBySortOrderAsc(Long masterProductId);
}
