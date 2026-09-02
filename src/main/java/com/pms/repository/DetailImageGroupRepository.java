package com.pms.repository;

import com.pms.domain.DetailImageGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code @TenantId} auto-filters query-based SELECTs to the current tenant — do NOT add manual tenant
 * conditions. Detail image groups are a tenant-wide catalog (no seller ownership, no default).
 *
 * <p>PK {@code findById()} is NOT tenant-filtered, so tenant-scoped single reads use {@link #findScopedById}
 * (a query-based SELECT), which yields empty for a cross-tenant id (natural 404).</p>
 */
public interface DetailImageGroupRepository extends JpaRepository<DetailImageGroup, Long> {

    /** The tenant's catalog in display order (creation order; there is no reorder endpoint). */
    List<DetailImageGroup> findAllByOrderBySortOrderAscIdAsc();

    /** Tenant-scoped fetch by id. Returns empty for a cross-tenant id (Hibernate @TenantId filter). */
    @Query("select g from DetailImageGroup g where g.id = :id")
    Optional<DetailImageGroup> findScopedById(@Param("id") Long id);

    boolean existsByCode(String code);

    boolean existsByName(String name);

    /**
     * Rename duplicate check. ⚠️ Must be used instead of {@link #existsByName(String)} on rename: saving a
     * group without actually changing its name would otherwise match itself and 400.
     */
    boolean existsByNameAndIdNot(String name, Long id);
}
