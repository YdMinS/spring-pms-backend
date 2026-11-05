package com.pms.repository;

import com.pms.domain.ListingMarketImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Marketplace photos of a listing (FEATURE_2611_11 / D8 · D21). Listing-scoped finders only — callers resolve the
 * tenant-scoped listing first (same rule as {@link GeneratedProductDataRepository}).
 */
public interface ListingMarketImageRepository extends JpaRepository<ListingMarketImage, Long> {

    /** Every photo of a listing in market order (option photos and product photos together). */
    List<ListingMarketImage> findByProductListingIdOrderBySortOrderAsc(Long productListingId);

    /** Whether a stored photo of this listing uses this url — a thumbnail sharing it must not be deleted. */
    boolean existsByProductListingIdAndImageUrl(Long productListingId, String imageUrl);

    /** Drop every photo row of a listing (the set is replaced at once; the listing deletion clears it first). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM ListingMarketImage i WHERE i.productListing.id = :listingId")
    void deleteByProductListingId(@Param("listingId") Long productListingId);
}
