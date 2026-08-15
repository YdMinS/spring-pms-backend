package com.pms.repository;

import com.pms.domain.MasterProduct;
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
}
