package com.pms.repository;

import com.pms.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
 * @see Category for entity definition
 * @see com.pms.service.CategoryService for business logic
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // No custom methods needed - JpaRepository provides all required functionality
}
