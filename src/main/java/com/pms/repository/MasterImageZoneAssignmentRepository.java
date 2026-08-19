package com.pms.repository;

import com.pms.domain.MasterImageZoneAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repository for {@link MasterImageZoneAssignment} — the field-to-image (M:N) mapping (FEATURE_2608_06 / 37).
 *
 * <p>No {@code @TenantId}; isolation flows through the master/image. Only master-/image-scoped finders are
 * exposed (no tenant-less {@code findAll}). Writes go through {@code saveAll(...)}; mapping replacement is
 * delete-then-insert.</p>
 */
public interface MasterImageZoneAssignmentRepository extends JpaRepository<MasterImageZoneAssignment, Long> {

    /** All of a master's mappings, ordered by zone then position (list + detail generator grouping). */
    List<MasterImageZoneAssignment> findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(Long masterProductId);

    /** One zone's mappings in render order. */
    List<MasterImageZoneAssignment> findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(
            Long masterProductId, String zoneId);

    /** Remove a master's mappings for one zone (setZoneImages / setSourceImage replace). */
    @Modifying
    @Transactional
    void deleteByImage_MasterProductIdAndZoneId(Long masterProductId, String zoneId);

    /** Remove all mappings of a pool image (image delete cleanup). */
    @Modifying
    @Transactional
    void deleteByImageId(Long imageId);
}
