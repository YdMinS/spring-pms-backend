package com.pms.repository;

import com.pms.domain.GeneratedProductData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link GeneratedProductData} (FEATURE_2608_06 / 3b-2).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent {@link com.pms.domain.ProductListing}
 * (callers resolve the tenant-scoped cell first). Only a listing-scoped finder is exposed (no tenant-less
 * {@code findAll} usage); the row is upserted per listing via {@link #findByProductListingId}.</p>
 */
public interface GeneratedProductDataRepository extends JpaRepository<GeneratedProductData, Long> {

    /** The single assets row for a listing (upsert lookup + generated-asset read). */
    Optional<GeneratedProductData> findByProductListingId(Long productListingId);
}
