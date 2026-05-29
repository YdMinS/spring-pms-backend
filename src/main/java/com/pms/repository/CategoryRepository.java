package com.pms.repository;

import com.pms.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
