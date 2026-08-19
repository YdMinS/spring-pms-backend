package com.pms.repository;

import com.pms.domain.ProductListingTagRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link ProductListingTagRevision} (33) — append-only tag snapshots per channel cell.
 *
 * <p>No {@code @TenantId} on the entity: rows are scoped through the {@code product_listing_id} FK and only
 * recorded after the parent listing has passed a tenant check.</p>
 */
@Repository
public interface ProductListingTagRevisionRepository extends JpaRepository<ProductListingTagRevision, Long> {

    /** The most recent snapshot for a cell (for the change-detection compare before an append). */
    Optional<ProductListingTagRevision> findTopByProductListing_IdOrderByIdDesc(Long listingId);
}
