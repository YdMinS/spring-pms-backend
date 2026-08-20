package com.pms.repository;

import com.pms.domain.ProcessingPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code @TenantId} auto-filters query-based SELECTs to the current tenant — do NOT add manual tenant
 * conditions. Processing presets are a tenant-wide library (no seller ownership, no default).
 *
 * <p>PK {@code findById()} is NOT tenant-filtered, so tenant-scoped single reads use {@link #findScopedById}
 * (a query-based SELECT), which yields empty for a cross-tenant id (natural 404).</p>
 */
public interface ProcessingPresetRepository extends JpaRepository<ProcessingPreset, Long> {

    /** Current tenant's presets, newest first (auto-scoped by {@code @TenantId}). */
    List<ProcessingPreset> findAllByOrderByIdDesc();

    /** Tenant-scoped fetch by id. Returns empty for a cross-tenant id (Hibernate @TenantId filter). */
    @Query("select p from ProcessingPreset p where p.id = :id")
    Optional<ProcessingPreset> findScopedById(@Param("id") Long id);
}
