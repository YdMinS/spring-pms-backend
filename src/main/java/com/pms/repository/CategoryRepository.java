package com.pms.repository;

import com.pms.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Category entity.
 * Provides CRUD operations via JpaRepository.
 *
 * Standard methods available:
 * - save(Category)
 * - findById(Long)
 * - findAll()
 * - delete(Category)
 * - deleteById(Long)
 *
 * Custom methods:
 * - findByPlatform(String platform) - Filter categories by platform
 *
 * @see Category for entity definition
 * @see com.pms.service.CategoryService for business logic
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByPlatform(String platform);

    // Tree browse (FEATURE_2608_06 / 52): root nodes, a parent's children, and leaf detection.
    List<Category> findByParentIsNull();

    List<Category> findByParentId(Long parentId);

    boolean existsByParentId(Long parentId);

    /**
     * Intermediate oclyx mirror match for the 53 import (a non-root path segment under a known parent).
     * Root-level segments use {@link #findByParentIsNull()} filtered by name.
     */
    Optional<Category> findByParentIdAndName(Long parentId, String name);
}
