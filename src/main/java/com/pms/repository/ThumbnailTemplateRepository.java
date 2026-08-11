package com.pms.repository;

import com.pms.domain.ThumbnailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@code @TenantId} auto-filters every query to the current tenant — do NOT add manual tenant
 * conditions. {@code sellerId} filtering is an explicit business filter (nullable column), so it is
 * fine to express here.
 */
public interface ThumbnailTemplateRepository extends JpaRepository<ThumbnailTemplate, Long> {

    List<ThumbnailTemplate> findBySellerId(Long sellerId);

    /** Active templates dedicated to a seller (thumbnail generation: first choice). */
    List<ThumbnailTemplate> findBySellerIdAndActiveTrue(Long sellerId);

    /** Active tenant-wide templates ({@code seller_id IS NULL}); fallback when no seller-specific one. */
    List<ThumbnailTemplate> findBySellerIdIsNullAndActiveTrue();
}
