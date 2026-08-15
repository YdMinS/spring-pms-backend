package com.pms.repository;

import com.pms.domain.ThumbnailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code @TenantId} auto-filters every query to the current tenant — do NOT add manual tenant
 * conditions. Templates are a tenant-wide library (no seller ownership); resolution for thumbnail
 * generation goes through the single active default.
 */
public interface ThumbnailTemplateRepository extends JpaRepository<ThumbnailTemplate, Long> {

    /** The tenant's single active default template (thumbnail generation source, isDefault uniqueness). */
    Optional<ThumbnailTemplate> findByIsDefaultTrueAndActiveTrue();
}
