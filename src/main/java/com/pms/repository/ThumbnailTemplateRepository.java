package com.pms.repository;

import com.pms.domain.ThumbnailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * {@code @TenantId} auto-filters every query to the current tenant — do NOT add manual tenant
 * conditions. Templates are a tenant-wide library (no seller ownership); resolution for thumbnail
 * generation goes through the single active default.
 */
public interface ThumbnailTemplateRepository extends JpaRepository<ThumbnailTemplate, Long> {

    /** The tenant's single active default template (thumbnail generation source, isDefault uniqueness). */
    Optional<ThumbnailTemplate> findByIsDefaultTrueAndActiveTrue();

    /**
     * Whether the template exists in the current tenant. A query-based SELECT, because the inherited PK
     * {@code findById()} is NOT filtered by {@code @TenantId} (see {@code MasterProductRepository#findScopedById})
     * — the image group check of {@code MasterProductImageServiceImpl#setZoneImages} (FEATURE_2610_33 D13).
     */
    @Query("select count(t) > 0 from ThumbnailTemplate t where t.id = :id")
    boolean existsScopedById(@Param("id") Long id);
}
