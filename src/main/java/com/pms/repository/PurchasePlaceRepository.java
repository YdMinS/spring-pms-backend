package com.pms.repository;

import com.pms.domain.PurchasePlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code @TenantId} auto-filters query-based SELECTs to the current tenant — do NOT add manual tenant
 * conditions (FEATURE_2609_76).
 *
 * <p>PK {@code findById()} is NOT tenant-filtered, so single reads use {@link #findScopedById} and id lists use
 * {@link #findScopedByIdIn} — a cross-tenant id simply does not come back.</p>
 */
public interface PurchasePlaceRepository extends JpaRepository<PurchasePlace, Long> {

    /** The tenant's list in display order (creation order; there is no reorder endpoint). */
    List<PurchasePlace> findAllByOrderBySortOrderAscIdAsc();

    /** Tenant-scoped fetch by id. Empty for a cross-tenant id (natural 404). */
    @Query("select p from PurchasePlace p where p.id = :id")
    Optional<PurchasePlace> findScopedById(@Param("id") Long id);

    /** Tenant-scoped fetch of several ids. A missing or cross-tenant id is simply absent from the result. */
    @Query("select p from PurchasePlace p where p.id in :ids")
    List<PurchasePlace> findScopedByIdIn(@Param("ids") Collection<Long> ids);

    boolean existsByName(String name);

    /** Rename duplicate check — excludes the row itself so saving an unchanged name does not 400. */
    boolean existsByNameAndIdNot(String name, Long id);
}
