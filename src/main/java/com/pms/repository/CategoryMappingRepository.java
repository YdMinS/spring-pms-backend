package com.pms.repository;

import com.pms.domain.CategoryMapping;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
