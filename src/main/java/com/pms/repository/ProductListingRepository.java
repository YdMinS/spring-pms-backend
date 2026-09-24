package com.pms.repository;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
    Page<ProductListing> findByPlatform(Platform platform, Pageable pageable);

    /**
     * Master-link filter for the listing screen (FEATURE_2609_22 / 04): the "마스터 미연결" tab lists exactly
     * the cells the 04 flow can act on (legacy `판매상품 등록` cells that know no master).
     *
     * @param platform Platform identifier (e.g., "COUPANG")
     * @param pageable Pagination information
     * @return Page of cells with no master link
     */
    Page<ProductListing> findByPlatformAndMasterProductIsNull(Platform platform, Pageable pageable);

    /**
     * Counterpart of {@link #findByPlatformAndMasterProductIsNull} — cells already grouped under a master.
     *
     * @param platform Platform identifier (e.g., "COUPANG")
     * @param pageable Pagination information
     * @return Page of cells that are linked to a master
     */
    Page<ProductListing> findByPlatformAndMasterProductIsNotNull(Platform platform, Pageable pageable);

    /**
     * Keyword half of the listing screen's search (판매상품 조회, 2026-09-25). Matched against two things:
     * <ul>
     *   <li>the cell <b>name</b> — case-insensitive partial match</li>
     *   <li>the <b>platform product id</b> (Coupang sellerProductId) — <b>exact</b> match</li>
     * </ul>
     *
     * <p>The id is matched exactly on purpose, mirroring {@code MasterProductRepository.searchPage}: a
     * numeric id under {@code like %..%} would drag in every longer id that merely contains it.</p>
     *
     * <p><b>Extension point</b>: another searchable field = one more {@code or} in this one constant, which
     * every variant below shares (so search never diverges between the master-link tabs).</p>
     */
    String SEARCH_PREDICATE =
            " and (lower(l.name) like lower(concat('%', :keyword, '%')) or l.platformProductId = :keyword)";

    /** Keyword variant of {@link #findByPlatform} — same rows, narrowed by {@link #SEARCH_PREDICATE}. */
    @Query(value = "select l from ProductListing l where l.platform = :platform" + SEARCH_PREDICATE,
           countQuery = "select count(l) from ProductListing l where l.platform = :platform" + SEARCH_PREDICATE)
    Page<ProductListing> searchByPlatform(@Param("platform") Platform platform,
                                          @Param("keyword") String keyword,
                                          Pageable pageable);

    /** Keyword variant of {@link #findByPlatformAndMasterProductIsNull}. */
    @Query(value = "select l from ProductListing l "
            + "where l.platform = :platform and l.masterProduct is null" + SEARCH_PREDICATE,
           countQuery = "select count(l) from ProductListing l "
            + "where l.platform = :platform and l.masterProduct is null" + SEARCH_PREDICATE)
    Page<ProductListing> searchByPlatformAndMasterProductIsNull(@Param("platform") Platform platform,
                                                                @Param("keyword") String keyword,
                                                                Pageable pageable);

    /** Keyword variant of {@link #findByPlatformAndMasterProductIsNotNull}. */
    @Query(value = "select l from ProductListing l "
            + "where l.platform = :platform and l.masterProduct is not null" + SEARCH_PREDICATE,
           countQuery = "select count(l) from ProductListing l "
            + "where l.platform = :platform and l.masterProduct is not null" + SEARCH_PREDICATE)
    Page<ProductListing> searchByPlatformAndMasterProductIsNotNull(@Param("platform") Platform platform,
                                                                   @Param("keyword") String keyword,
                                                                   Pageable pageable);

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
     * Batch variant of {@link #findByMasterProductId(Long)} — the option market-lock judgement (84) resolves
     * every master's cells in ONE query so the master list endpoint stays free of an N+1. Tenant-filtered by
     * {@code @TenantId} automatically.
     *
     * @param masterProductIds ids of the parent MasterProducts
     * @return List of ProductListing cells across all given masters
     */
    List<ProductListing> findByMasterProductIdIn(Collection<Long> masterProductIds);

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
     * 신규 채널 등록(FEATURE_2608_06 / 3b')의 중복 가드. 같은 마스터를 같은 계정에 <b>새로</b> 올리는 것은
     * 마켓에 중복 상품을 만드는 일이라 계속 막는다.
     *
     * <p>🔴 이것은 더 이상 데이터 불변식이 아니다(온보딩 2026-09-19, 2609_22/D18 부분 번복). 쿠팡에 이미
     * 있는 상품을 편입하는 경로({@code CoupangListingImportServiceImpl})는 이 가드를 쓰지 않는다 —
     * 같은 물건을 페이지 여러 개로 파는 것이 정상 판매 방식이기 때문이다. DB 유니크 제약도 없다.
     * 따라서 "(master, seller, platform) 셀은 하나" 를 전제로 코드를 쓰지 말 것.</p>
     *
     * @param masterProductId parent MasterProduct id
     * @param sellerId        seller id
     * @param platform        platform identifier (e.g., "COUPANG")
     * @return true if a listing already exists for that account under the master
     */
    boolean existsByMasterProductIdAndSellerIdAndPlatform(Long masterProductId, Long sellerId, Platform platform);

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
     * Repricing candidates (FEATURE_2609_39 / PLAN D6·D17·D20): the cells whose option prices may be judged and
     * pushed. Tenant-filtered by {@code @TenantId} automatically.
     *
     * <p>🔴 The two hard rules are in the query, not in Java: {@code status = SELLING} (D20 — a suspended or
     * rejected cell still carries market identifiers, so leaving it in would end with us repricing something
     * that is not for sale) and {@code platform = COUPANG} (D17 — no other adapter can update a price, and
     * filtering afterwards only means reading rows to throw them away).</p>
     *
     * @param sellerId  restrict to one seller; null = every seller
     * @param platform  restrict to one platform; null = every (supported) platform
     * @return SELLING Coupang cells for the current tenant
     */
    @Query("select l from ProductListing l "
            + "where l.status = com.pms.domain.ListingStatus.SELLING "
            + "and l.platform = com.pms.domain.Platform.COUPANG "
            + "and (:sellerId is null or l.seller.id = :sellerId) "
            + "and (:platform is null or l.platform = :platform)")
    List<ProductListing> findRepricingTargets(@Param("sellerId") Long sellerId,
                                              @Param("platform") Platform platform);

    /**
     * Pending market-sync source (FEATURE_2608_06 / 3d, pending-sync / push-sync): cells regenerated locally
     * by layer A but not yet pushed to the market. Derived-query SELECT, so tenant-filtered by {@code @TenantId}
     * automatically (only the current tenant's cells).
     *
     * @return listings flagged {@code needs_market_sync = true} for the current tenant
     */
    List<ProductListing> findByNeedsMarketSyncTrue();
}
