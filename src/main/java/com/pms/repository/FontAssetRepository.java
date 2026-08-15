package com.pms.repository;

import com.pms.domain.FontAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@link FontAsset} is NOT {@code @TenantId} (system fonts have {@code tenant_id = null}); tenant
 * scoping is therefore expressed manually here: system ∪ current tenant.
 */
public interface FontAssetRepository extends JpaRepository<FontAsset, Long> {

    /** System fonts (tenant_id null) plus the given tenant's fonts — the editor dropdown source. */
    @Query("SELECT f FROM FontAsset f WHERE f.tenantId IS NULL OR f.tenantId = :tenantId ORDER BY f.id")
    List<FontAsset> findSystemAndTenant(@Param("tenantId") Long tenantId);

    /** Idempotent-seed lookup: a system (shared) font by family key. */
    Optional<FontAsset> findByFamilyKeyAndTenantIdIsNull(String familyKey);
}
