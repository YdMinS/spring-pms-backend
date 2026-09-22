package com.pms.repository;

import com.pms.domain.ProductListingOption;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ProductListingOption entity.
 *
 * Provides CRUD operations and custom queries for product listing options.
 */
@Repository
public interface ProductListingOptionRepository extends JpaRepository<ProductListingOption, Long> {

    /**
     * Find all options for a specific product listing.
     *
     * @param productListingId ID of the parent ProductListing
     * @return List of ProductListingOption entities
     */
    List<ProductListingOption> findByProductListingId(Long productListingId);

    /**
     * Batch-fetch options for several listings at once (coverage matrix selling-price lookup, N+1 guard).
     *
     * @param productListingIds IDs of the parent ProductListings
     * @return List of ProductListingOption entities across all given listings
     */
    List<ProductListingOption> findByProductListingIdIn(Collection<Long> productListingIds);

    /**
     * Find an option by platform option ID.
     *
     * @param platformOptionId Platform-specific option ID
     * @return Optional containing the ProductListingOption if found
     */
    Optional<ProductListingOption> findByPlatformOptionId(String platformOptionId);

    /**
     * Check if an option exists with the given platform option ID.
     *
     * @param platformOptionId Platform-specific option ID
     * @return true if exists, false otherwise
     */
    boolean existsByPlatformOptionId(String platformOptionId);

    /**
     * Delete all options for a specific product listing.
     *
     * @param productListingId ID of the parent ProductListing
     */
    void deleteByProductListingId(Long productListingId);

    /**
     * Bulk-load options with everything the channel-config resolver needs, in ONE query
     * (FEATURE_2609_30 / 03 Step 3).
     *
     * <p>⚠️ Commission / delivery / box are resolved per cell, and the sales report touches many options at
     * once. Loading them one by one — or letting {@code productListing} / {@code masterProductOption} lazy-load
     * per row — is an immediate N+1 on a report that already scans a whole month.
     */
    @EntityGraph(attributePaths = {"productListing", "productListing.masterProduct",
            "masterProductOption", "masterProductOption.masterProduct"})
    List<ProductListingOption> findWithConfigByIdIn(Collection<Long> ids);

    /**
     * 주어진 마스터 옵션들에 연결된 채널 옵션 + 그 셀·판매자 (FEATURE_2609_71 / 04).
     *
     * <p>「이 물품을 쓰는 판매 옵션은 무엇인가」의 <b>역방향</b> 조회다. 2609_71 이전에는
     * {@code product_listing_product} 를 물품 id 로 뒤졌지만, 셀 구성품 사본이 사라져 이제는
     * 마스터 옵션({@code master_product_option_item})에서 출발해 FK 를 타고 내려온다.
     *
     * <p>⚠️ {@code productListing} · {@code seller} 를 함께 읽는다 — 호출부가 셀의 판매자·플랫폼·상태를
     * 곧바로 쓰므로 LAZY 로 두면 행마다 쿼리가 나간다.
     */
    @EntityGraph(attributePaths = {"productListing", "productListing.seller"})
    List<ProductListingOption> findByMasterProductOption_IdIn(Collection<Long> masterOptionIds);
}
