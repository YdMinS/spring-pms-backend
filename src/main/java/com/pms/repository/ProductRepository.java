package com.pms.repository;

import com.pms.domain.Product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ProductRepository - JPA repository for Product entity
 *
 * Phase 2-1: Only uses save() (inherited from JpaRepository)
 * Phase 2-3: Adds findByActiveTrue() for getAllProducts
 * Phase 2-3: Adds searchByKeyword() for search functionality
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Find active products (for Phase 2-3 READ implementation)
     */
    Page<Product> findByActiveTrue(Pageable pageable);

    /**
     * Active products matched by ONE keyword against five things.
     *
     * <ul>
     *   <li><b>productName</b>, <b>brand</b>, <b>description</b> — case-insensitive <b>partial</b> match</li>
     *   <li><b>the product's own oclyx id</b> — <b>exact</b> match</li>
     *   <li><b>barcodeId</b> — <b>exact</b> match</li>
     * </ul>
     *
     * <p>🔴 The id is matched exactly on purpose: a number under {@code like %..%} would drag in every
     * longer id that merely contains it (mirrors {@code MasterProductRepository.searchPage}, 110).
     * Comparing the number itself rather than {@code cast(p.id as string)} also keeps the primary key
     * index usable.</p>
     *
     * <p>🔴 The barcode is matched exactly for the same reason, and the reason bites harder here: a
     * 13-digit EAN under {@code like %..%} would be dragged in by any short number the operator types.
     * The comparison is on the string as given — a barcode is not necessarily numeric (CODE_128), so it
     * is never parsed as a number. {@code (tenant_id, barcode_id)} is unique since changeset 098, so
     * within a tenant an exact hit is at most one product. {@code barcode_id} is NULL — never {@code ''} —
     * when absent (changeset 100 normalised the blanks, and the service stores blank as null), and a blank
     * keyword never reaches this query at all: {@code getAllProducts} sends it to {@code findByActiveTrue}.</p>
     *
     * <p>{@code idValue} is the same keyword pre-parsed by the service: {@code null} whenever the keyword
     * is not a plain {@code Long} (text, or a number too large), which switches the id branch off and
     * leaves the text match alone. There is no parameter selecting WHAT to search — the keyword is one,
     * and only the server knows what an id or a barcode looks like (2609_60 / D3·D8).</p>
     */
    @Query("SELECT p FROM Product p WHERE p.active = true " +
           "AND (LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR p.barcodeId = :keyword " +
           "OR (:idValue IS NOT NULL AND p.id = :idValue))")
    Page<Product> searchByKeyword(@Param("keyword") String keyword,
                                  @Param("idValue") Long idValue,
                                  Pageable pageable);

    /**
     * Tenant-scoped fetch by id. Returns empty for a cross-tenant id.
     *
     * <p>The inherited PK {@code findById()} is NOT tenant-filtered by Hibernate's {@code @TenantId}
     * (only query-derived SELECTs are), so ownership checks that must respect tenant boundaries use this
     * query-based finder — a cross-tenant id yields empty → a natural 404 with no manual tenant compare.
     * Mirrors {@code MasterProductRepository.findScopedById}. Used by {@code ProductImageService} (39).</p>
     */
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findScopedById(@Param("id") Long id);

    /**
     * Is this URL still used as some product's representative image?
     *
     * <p>The empty-gallery rule keeps {@code product.imageUrl} alive after the last gallery row is gone, so a
     * shared storage object can be referenced with no {@code product_image} row left. A derived query, so
     * Hibernate's {@code @TenantId} filter applies automatically (see {@code findScopedById}).
     * ⚠️ Representatives of soft-deleted ({@code active = false}) products count too — the file
     * simply survives longer (orphan file; cleanup is out of scope). Used by the FEATURE_2609_62 delete guard.</p>
     */
    boolean existsByImageUrl(String imageUrl);

    /**
     * Check if a product with given barcode exists
     */
    boolean existsByBarcodeId(String barcodeId);

    /**
     * Find product by barcode ID
     */
    Optional<Product> findByBarcodeId(String barcodeId);

    /**
     * Every product carrying this barcode, soft-deleted ones included (barcode uniqueness guard).
     *
     * <p>Returns a list, not an {@code Optional}: the guard has to run on databases that still hold a
     * legacy duplicate pair, and {@code findByBarcodeId} would blow up with a non-unique result there
     * instead of reporting the clash. Soft-deleted rows count because the DB key counts them too
     * (changeset 098) — the two must agree. In practice a hidden row no longer holds a barcode at all:
     * {@code ProductServiceImpl.deleteProduct} releases it on delete and changeset 099 cleared the rows
     * deleted before that, so this finder returns active owners only. Derived query → Hibernate's
     * {@code @TenantId} filter applies, so the check is tenant-scoped exactly like the constraint.</p>
     */
    List<Product> findAllByBarcodeId(String barcodeId);

    /**
     * Distinct tenant ids across all products, ignoring the {@code @TenantId} filter.
     *
     * <p>Native query so it bypasses Hibernate's tenant discriminator — there is no separate Tenant
     * registry (the tenant dimension is a {@code tenant_id} column only). Used by the one-off S3
     * migration runner to iterate every tenant from a non-web/boot context.</p>
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM products", nativeQuery = true)
    List<Long> findDistinctTenantIds();

    /**
     * Active products carrying this barcode (FEATURE_2609_69 / B, merge guard).
     *
     * <p>The whole point of a merge is to remove a duplicate barcode, so the merge refuses to create a new
     * one: if a THIRD active product already owns the chosen barcode, the request is a 409. Derived query →
     * Hibernate's {@code @TenantId} filter applies. Soft-deleted products are excluded on purpose — they
     * are exactly what a merge leaves behind, and a later unique index on {@code barcode_id} will have to
     * be partial on {@code active} for the same reason.</p>
     */
    List<Product> findByBarcodeIdAndActiveTrue(String barcodeId);
}
