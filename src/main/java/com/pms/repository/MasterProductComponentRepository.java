package com.pms.repository;

import com.pms.domain.MasterProductComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * Repository for {@link MasterProductComponent} (FEATURE_2608_06 / 3b-1).
 *
 * <p>The entity has no {@code @TenantId} — isolation flows through the parent master, so only
 * master-scoped finders are exposed (no tenant-less {@code findAll} usage). Component sets are updated
 * by {@link #deleteByMasterProductId} + re-insert.</p>
 */
public interface MasterProductComponentRepository extends JpaRepository<MasterProductComponent, Long> {

    List<MasterProductComponent> findByMasterProductId(Long masterProductId);

    /**
     * Masters this product is a component of (FEATURE_2609_69 / A).
     *
     * <p>{@code @EntityGraph} pulls the master in with the row: the usage response prints the master name
     * and {@code masterProduct} is LAZY, so waking it per row would be an N+1.</p>
     *
     * <p>⚠️ Not tenant-scoped (this entity has no {@code @TenantId}); the caller resolves the product
     * through the tenant-filtered {@code ProductRepository} first.</p>
     */
    @EntityGraph(attributePaths = "masterProduct")
    List<MasterProductComponent> findByProductId(Long productId);

    /**
     * (productId, listingId) pairs for the channel-count column on the product list (2026-09-23).
     *
     * <p>Same definition as the usage screen: a master this product is a component of, and every channel
     * cell linked to that master ({@code ProductListingRepository#findByMasterProductIdIn}). Returned as
     * pairs — not a {@code count} — because the caller unions these with the option-item path and a
     * listing reachable through both must be counted once.</p>
     *
     * <p>🔴 Tenant isolation is structural: the ad-hoc join targets {@code ProductListing}, which carries
     * {@code @TenantId}, so Hibernate filters it automatically. Do not add a manual tenant condition.</p>
     */
    @Query("select distinct c.product.id, l.id from MasterProductComponent c "
            + "join ProductListing l on l.masterProduct.id = c.masterProduct.id "
            + "where c.product.id in :productIds")
    List<Object[]> findChannelListingIdsByProductIds(@Param("productIds") Collection<Long> productIds);

    void deleteByMasterProductId(Long masterProductId);

    /**
     * Masters that contain <b>every</b> product in {@code productIds} (superset match, order-independent).
     *
     * <p>Step 1 of the exact-set lookup: {@code group by ... having count(distinct product) = size} keeps
     * only masters covering the whole requested set. A master with <i>extra</i> components still passes
     * here — {@link #findMasterIdsWithComponentCount} narrows it down to an exact match.</p>
     *
     * <p>⚠️ Not tenant-scoped (this entity has no {@code @TenantId}); the caller resolves the ids through
     * the tenant-filtered {@code MasterProductRepository#findScopedByIdIn}.</p>
     *
     * @param productIds deduped component product ids (never empty)
     * @param size       {@code productIds.size()}
     */
    @Query("select c.masterProduct.id from MasterProductComponent c "
            + "where c.product.id in :productIds "
            + "group by c.masterProduct.id "
            + "having count(distinct c.product.id) = :size")
    List<Long> findMasterIdsCoveringAll(@Param("productIds") Collection<Long> productIds,
                                        @Param("size") long size);

    /**
     * Of {@code masterIds}, the ones whose <b>total</b> component count equals {@code size}.
     *
     * <p>Step 2 of the exact-set lookup: combined with {@link #findMasterIdsCoveringAll} (covers all N
     * requested) this leaves exactly "same set, no extras" — a superset master is dropped here.</p>
     */
    @Query("select c.masterProduct.id from MasterProductComponent c "
            + "where c.masterProduct.id in :masterIds "
            + "group by c.masterProduct.id "
            + "having count(c) = :size")
    List<Long> findMasterIdsWithComponentCount(@Param("masterIds") Collection<Long> masterIds,
                                               @Param("size") long size);
}
