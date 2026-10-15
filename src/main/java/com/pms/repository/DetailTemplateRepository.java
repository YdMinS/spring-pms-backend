package com.pms.repository;

import com.pms.domain.DetailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code @TenantId} auto-filters query-based SELECTs to the current tenant — do NOT add manual tenant
 * conditions. Detail templates are a tenant-wide library (no seller ownership).
 *
 * <p>PK {@code findById()} is NOT tenant-filtered, so tenant-scoped single reads use {@link #findScopedById}
 * (a query-based SELECT), which yields empty for a cross-tenant id (natural 404).</p>
 */
public interface DetailTemplateRepository extends JpaRepository<DetailTemplate, Long> {

    /** The tenant's single active default template (seeder guarantees exactly one). */
    Optional<DetailTemplate> findByIsDefaultTrueAndActiveTrue();

    /** Tenant-scoped fetch by id. Returns empty for a cross-tenant id (Hibernate @TenantId filter). */
    @Query("select d from DetailTemplate d where d.id = :id")
    Optional<DetailTemplate> findScopedById(@Param("id") Long id);

    /**
     * Every tenant id that owns a detail template, ignoring the {@code @TenantId} filter (native query) — the
     * startup {@code NoticeBlockCleanupRunner} sets each tenant explicitly (FEATURE_2610_16 / D23).
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM detail_template", nativeQuery = true)
    List<Long> findDistinctTenantIds();
}
