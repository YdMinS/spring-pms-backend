package com.pms.repository;

import com.pms.domain.MasterProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link MasterProduct} (FEATURE_2608_06 / 3a).
 *
 * <p>{@code findAll()} is tenant-filtered by {@code @TenantId} automatically. The inherited PK
 * {@code findById()} is NOT tenant-filtered, so tenant-scoped reads use {@link #findScopedById}
 * (a query-based SELECT, which Hibernate's {@code @TenantId} filter applies to) — this yields
 * empty for a cross-tenant id, giving a natural 404 without a manual tenant compare.</p>
 */
public interface MasterProductRepository extends JpaRepository<MasterProduct, Long> {

    /** Tenant-scoped fetch by id. Returns empty for a cross-tenant id (Hibernate @TenantId filter). */
    @Query("select m from MasterProduct m where m.id = :id")
    Optional<MasterProduct> findScopedById(@Param("id") Long id);

    /**
     * Tenant-scoped batch fetch by id — the plural of {@link #findScopedById}. Cross-tenant ids are simply
     * absent from the result (Hibernate @TenantId filter), which is how the by-components lookup keeps
     * another tenant's masters out of the duplicate check.
     *
     * <p>Includes soft-deleted ({@code active = false}) masters on purpose: a hidden duplicate is exactly
     * the case the create screen must warn about.</p>
     */
    @Query("select m from MasterProduct m where m.id in :ids")
    java.util.List<MasterProduct> findScopedByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /**
     * Active masters only — excludes soft-deleted (active=false) rows. @TenantId auto-filters tenant
     * (derived queries are HQL-based, so the filter applies; only the inherited PK findById is exempt).
     *
     * <p>Paged since 110 — the sort comes from the {@link Pageable} the service builds off a whitelist.</p>
     */
    Page<MasterProduct> findByActiveTrue(Pageable pageable);

    /**
     * Active masters matched by ONE keyword against three things (FEATURE_2609_60 / D1·D2):
     * <ul>
     *   <li>master <b>name</b> — case-insensitive partial match (unchanged)</li>
     *   <li><b>platform product id</b> — the marketplace listing id the user typed when creating the
     *       master (Coupang sellerProductId) — <b>exact</b> match</li>
     *   <li><b>platform option id</b> — the marketplace option id (Coupang vendorItemId), the value the
     *       purchase screen shows for unregistered orders — <b>exact</b> match</li>
     * </ul>
     *
     * <p>Ids are matched exactly on purpose: a numeric id under {@code like %..%} would drag in every
     * longer id that merely contains it.</p>
     *
     * <p>Neither the cell's status nor the option's {@code active} flag filters the match (D4) — the
     * question being asked is "which master owns this id", not "is it selling". Only the master's own
     * {@code active = true} still applies.</p>
     *
     * <p>Tenant isolation comes from the correlation alone (D5): the outer root {@code MasterProduct}
     * carries {@code @TenantId}, and the subqueries are tied to it by {@code l.masterProduct = m}.
     * 🔴 Do NOT add a manual tenant condition.</p>
     *
     * <p><b>Extension point</b>: another platform's id = one more {@code or exists} here. Nothing above
     * this method (service / controller / front) knows which kind of id the keyword was (D8).</p>
     */
    @Query(value = "select m from MasterProduct m where m.active = true and ("
            + "lower(m.name) like lower(concat('%', :keyword, '%')) "
            + "or exists (select l.id from ProductListing l "
            + "           where l.masterProduct = m and l.platformProductId = :keyword) "
            + "or exists (select o.id from ProductListingOption o "
            + "           where o.productListing.masterProduct = m and o.platformOptionId = :keyword))",
           countQuery = "select count(m) from MasterProduct m where m.active = true and ("
            + "lower(m.name) like lower(concat('%', :keyword, '%')) "
            + "or exists (select l.id from ProductListing l "
            + "           where l.masterProduct = m and l.platformProductId = :keyword) "
            + "or exists (select o.id from ProductListingOption o "
            + "           where o.productListing.masterProduct = m and o.platformOptionId = :keyword))")
    Page<MasterProduct> searchActivePage(@Param("keyword") String keyword, Pageable pageable);
}
