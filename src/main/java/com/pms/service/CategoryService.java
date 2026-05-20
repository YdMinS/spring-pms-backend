package com.pms.service;

import com.pms.dto.request.CreateCategoryRequest;
import com.pms.dto.request.UpdateCategoryRequest;
import com.pms.dto.response.CategoryResponse;

import java.util.List;

/**
 * Service interface for Category management.
 * Defines all business operations for categories.
 *
 * @see com.pms.service.CategoryServiceImpl for implementation
 */
public interface CategoryService {

    /**
     * Create a new category.
     *
     * @param request CreateCategoryRequest containing category details
     * @return CategoryResponse with created category data
     * @throws com.pms.exception.ResourceNotFoundException if parent category not found
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * Retrieve a category by ID.
     *
     * @param id Category ID
     * @return CategoryResponse with category data
     * @throws com.pms.exception.ResourceNotFoundException if category not found
     */
    CategoryResponse getCategoryById(Long id);

    /**
     * Retrieve all categories.
     *
     * @return List of all categories
     */
    List<CategoryResponse> getAllCategories();

    /**
     * Update an existing category.
     *
     * @param id Category ID to update
     * @param request UpdateCategoryRequest containing updated details
     * @return CategoryResponse with updated category data
     * @throws com.pms.exception.ResourceNotFoundException if category or parent not found
     * @throws IllegalArgumentException if attempting self-reference (parentId == id)
     */
    CategoryResponse updateCategory(Long id, UpdateCategoryRequest request);

    /**
     * Delete a category.
     * Children's parent_id will become null (orphaned).
     *
     * @param id Category ID to delete
     * @throws com.pms.exception.ResourceNotFoundException if category not found
     */
    void deleteCategory(Long id);
}
