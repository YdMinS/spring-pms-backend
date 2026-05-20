package com.pms.service;

import com.pms.domain.Category;
import com.pms.dto.request.CreateCategoryRequest;
import com.pms.dto.request.UpdateCategoryRequest;
import com.pms.dto.response.CategoryResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service implementation for Category management.
 *
 * Handles all business logic including:
 * - Parent category validation
 * - Self-reference prevention
 * - Data persistence via CategoryRepository
 * - Transactional boundaries
 *
 * @see CategoryService for interface contract
 * @see CategoryRepository for data access
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", request.parentId()));
        }

        Category category = Category.builder()
            .name(request.name())
            .platform(request.platform())
            .platformCategoryId(request.platformCategoryId())
            .parent(parent)
            .build();

        Category saved = categoryRepository.save(category);
        return toResponse(saved);
    }

    @Override
    public CategoryResponse getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        return toResponse(category);
    }

    @Override
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll()
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(Long id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Category", id));

        Category parent = null;
        if (request.parentId() != null) {
            if (request.parentId().equals(id)) {
                throw new IllegalArgumentException("Category cannot be its own parent");
            }

            parent = categoryRepository.findById(request.parentId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", request.parentId()));
        }

        Category updated = Category.builder()
            .id(category.getId())
            .name(request.name())
            .platform(request.platform())
            .platformCategoryId(request.platformCategoryId())
            .parent(parent)
            .build();

        Category saved = categoryRepository.save(updated);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Category", id));

        categoryRepository.delete(category);
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
            .id(category.getId())
            .name(category.getName())
            .platform(category.getPlatform())
            .platformCategoryId(category.getPlatformCategoryId())
            .parentId(category.getParent() != null ? category.getParent().getId() : null)
            .createdDate(category.getCreatedAt())
            .modifiedDate(category.getUpdatedAt())
            .build();
    }
}
