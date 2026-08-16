package com.pms.repository;

import com.pms.domain.ProductListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ProductListing entity.
 *
 * Provides CRUD operations and custom queries for platform product listings.
 */
@Repository
public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {

    /**
     * Find all product listings for a specific platform with pagination.
     *
     * @param platform Platform identifier (e.g., "COUPANG")
     * @param pageable Pagination information
     * @return Page of ProductListing entities
     */
    Page<ProductListing> findByPlatform(String platform, Pageable pageable);

    /**
     * Find a product listing by platform product ID.
     *
     * @param platformProductId Platform's product ID
     * @return Optional containing the ProductListing if found
     */
    Optional<ProductListing> findByPlatformProductId(String platformProductId);

    /**
     * Check if a product listing exists for the given platform product ID.
     *
     * @param platformProductId Platform's product ID
     * @return true if exists, false otherwise
     */
    boolean existsByPlatformProductId(String platformProductId);

    /**
     * Find all channel-cell listings grouped under a master product (coverage matrix right side).
     * Tenant-filtered by {@code @TenantId} automatically.
     *
     * @param masterProductId ID of the parent MasterProduct
     * @return List of ProductListing cells for that master
     */
    List<ProductListing> findByMasterProductId(Long masterProductId);

    /**
     * Tenant-scoped fetch by id (FEATURE_2608_06 / 3b-2). The inherited PK {@code findById} is NOT
     * tenant-filtered; this query-based SELECT is (Hibernate {@code @TenantId} filter), so a cross-tenant
     * id yields empty → a natural 404 without a manual tenant compare (mirrors MasterProductRepository).
     *
     * @param id ProductListing id
     * @return Optional containing the listing if it belongs to the current tenant
     */
    @Query("select p from ProductListing p where p.id = :id")
    Optional<ProductListing> findScopedById(@Param("id") Long id);

    /**
     * Channel-add duplicate guard (FEATURE_2608_06 / 3b'): at most one cell per (master, seller, platform)
     * — one market product page per account. Tenant-filtered by {@code @TenantId} automatically.
     *
     * @param masterProductId parent MasterProduct id
     * @param sellerId        seller id
     * @param platform        platform identifier (e.g., "COUPANG")
     * @return true if a listing already exists for that account under the master
     */
    boolean existsByMasterProductIdAndSellerIdAndPlatform(Long masterProductId, Long sellerId, String platform);

    /**
     * Pending-approval sweep source (FEATURE_2608_06 / 3c, sync-approvals): cells still SUBMITTED that have at
     * least one option not yet approved. Tenant-filtered by {@code @TenantId} on ProductListing automatically.
     *
     * <p>Uses an explicit entity join to ProductListingOption (that entity has no {@code @TenantId} and there
     * is no mapped {@code options} collection on ProductListing — kept unchanged), with DISTINCT to dedup a
     * listing that has several not-approved options.</p>
     *
     * @return distinct listings awaiting approval for the current tenant
     */
    @Query("SELECT DISTINCT l FROM ProductListing l "
            + "JOIN ProductListingOption o ON o.productListing = l "
            + "WHERE l.status = com.pms.domain.ListingStatus.SUBMITTED "
            + "AND o.approvalStatus = com.pms.domain.OptionApprovalStatus.NOT_APPROVED")
    List<ProductListing> findPendingApproval();

    /**
     * Pending market-sync source (FEATURE_2608_06 / 3d, pending-sync / push-sync): cells regenerated locally
     * by layer A but not yet pushed to the market. Derived-query SELECT, so tenant-filtered by {@code @TenantId}
     * automatically (only the current tenant's cells).
     *
     * @return listings flagged {@code needs_market_sync = true} for the current tenant
     */
    List<ProductListing> findByNeedsMarketSyncTrue();
}
