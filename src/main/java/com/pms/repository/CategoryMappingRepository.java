package com.pms.repository;

import com.pms.domain.CategoryMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link CategoryMapping} (FEATURE_2608_06 / 44).
 *
 * <p>⚠️ {@code category_mapping} has no tenant column — it must be reached only via a category id. Only
 * category-scoped finders are exposed here; the inherited tenant-less {@code findAll} / a plain
 * {@code findByPlatform} must not be used for lookups (cross-tenant leak).</p>
 */
public interface CategoryMappingRepository extends JpaRepository<CategoryMapping, Long> {

    List<CategoryMapping> findByCategoryId(Long categoryId);

    Optional<CategoryMapping> findByCategoryIdAndPlatform(Long categoryId, String platform);

    boolean existsByCategoryIdAndPlatform(Long categoryId, String platform);

    /**
     * Reverse lookup of a leaf mirror by its {@link com.pms.domain.PlatformCategory} FK (FEATURE_2608_06 / 53).
     *
     * <p>Rename-safe: the 53 import re-runs use this to detect an already-created oclyx mirror leaf even after
     * the user renamed it — the FK ({@code platform_category_id_fk}) is stable while the name is not. Backed by
     * JPQL (not a derived name) to disambiguate from the deprecated String {@code platformCategoryId} column.
     * The import guarantees a single mapping per PlatformCategory, so {@code Optional} is safe here.</p>
     */
    @Query("SELECT cm FROM CategoryMapping cm WHERE cm.platformCategory.id = :platformCategoryId")
    Optional<CategoryMapping> findByPlatformCategoryId(@Param("platformCategoryId") Long platformCategoryId);
}
