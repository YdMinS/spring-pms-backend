package com.pms.repository;

import com.pms.domain.BoxRecipe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for the box memory (FEATURE_2609_40 / PLAN D22 · D23).
 *
 * <p>⚠️ {@code BoxRecipe} is {@code @TenantId}-scoped, so both queries are tenant-filtered by Hibernate —
 * never add a manual tenant condition.</p>
 */
public interface BoxRecipeRepository extends JpaRepository<BoxRecipe, Long> {

    /**
     * Candidates for a combination, best first: most used, then most recently used (D23).
     * The 3-item cap and the "is this box still usable" filter live in the service, not here.
     */
    List<BoxRecipe> findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(String recipeKey);

    /** The single row the unique constraint {@code (tenant_id, recipe_key, package_id)} allows. */
    Optional<BoxRecipe> findByRecipeKeyAndBoxPackage_Id(String recipeKey, Long packageId);

    /**
     * Delete every box memory whose combination contains this product (FEATURE_2609_69 / B, PLAN D12).
     *
     * <p>The key is {@code "id:qty|id:qty…"} sorted by product id, so the id sits either at the head of the
     * string or right after a {@code '|'} — those two LIKE patterns are exact, while a naive
     * {@code LIKE '%589%'} would also delete {@code "1589:2"} and {@code "12:589"}, i.e. other products'
     * rows.</p>
     *
     * <p>🔴 The key is NOT rewritten to the target id: it is a sorted string, so a rewrite would collide
     * with the target's own key under {@code uq_box_recipe_key_package}. Deleting is cheap — this table is
     * a memory that rebuilds itself every time a parcel is packed.</p>
     *
     * <p>⚠️ Native query, so Hibernate's {@code @TenantId} filter does NOT apply. That is safe here because
     * product ids are globally unique (one {@code products} table with a tenant column), so no other
     * tenant's key can legitimately contain this id.</p>
     *
     * @return number of rows deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM box_recipe WHERE recipe_key LIKE CONCAT(:pid, ':%') "
                 + "OR recipe_key LIKE CONCAT('%|', :pid, ':%')", nativeQuery = true)
    int deleteByProductIdInKey(@Param("pid") Long productId);
}
