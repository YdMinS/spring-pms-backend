package com.pms.repository;

import com.pms.domain.MasterProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link MasterProduct} (FEATURE_2608_06 / 3a).
 *
 * <p>{@code findAll()} is tenant-filtered by {@code @TenantId} automatically. The inherited PK
 * {@code findById()} is NOT tenant-filtered, so tenant-scoped reads use {@link #findScopedById}
 * (a query-based SELECT, which Hibernate's {@code @TenantId} filter applies to) — this yields
 * empty for a cross-tenant id, giving a natural 404 without a manual tenant compare.</p>
 */
public interface MasterProductRepository extends JpaRepository<MasterProduct, Long> {

    /** Tenant-scoped fetch by id. Returns empty for a cross-tenant id (Hibernate @TenantId filter). */
    @Query("select m from MasterProduct m where m.id = :id")
    Optional<MasterProduct> findScopedById(@Param("id") Long id);

    /**
     * Active masters only — excludes soft-deleted (active=false) rows. @TenantId auto-filters tenant
     * (derived queries are HQL-based, so the filter applies; only the inherited PK findById is exempt).
     *
     * <p>Paged since 110 — the sort comes from the {@link Pageable} the service builds off a whitelist.</p>
     */
    Page<MasterProduct> findByActiveTrue(Pageable pageable);

    /**
     * Active masters whose name contains {@code keyword} (case-insensitive partial match), paged.
     * The service passes an already-trimmed, non-blank keyword. @TenantId auto-filters tenant.
     */
    @Query("select m from MasterProduct m where m.active = true " +
           "and lower(m.name) like lower(concat('%', :keyword, '%'))")
    Page<MasterProduct> searchActiveByNamePage(@Param("keyword") String keyword, Pageable pageable);
}
