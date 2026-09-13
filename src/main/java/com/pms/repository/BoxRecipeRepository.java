package com.pms.repository;

import com.pms.domain.BoxRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
