package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.request.CreateCategoryRequest;
import com.pms.dto.request.UpdateCategoryRequest;
import com.pms.dto.response.CategoryResponse;
import com.pms.dto.response.CategoryTreeNode;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
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
    private final CategoryMappingRepository categoryMappingRepository;
    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        // FEATURE_2610_05 / D32: a new node is a leaf the moment it exists, so it is saved together with its
        // platform mapping in this one transaction — never create-then-map (a failed second call would leave a
        // leaf with no mapping). D38: the mapping links the platform_category row of (platform, code), the same
        // way PUT .../mappings stores it; a code missing from that list → 400. No commission prefill: its
        // REQUIRES_NEW transaction cannot see this uncommitted category, so it would be a no-op.
        CategoryMappingRequest mapping = request.mapping();
        if (mapping == null
            || !StringUtils.hasText(mapping.getPlatform())
            || !StringUtils.hasText(mapping.getPlatformCategoryId())) {
            throw new IllegalArgumentException("플랫폼 카테고리를 함께 선택해야 합니다.");
        }
        Platform mappingPlatform = Platform.from(mapping.getPlatform());
        PlatformCategory platformCategory = platformCategoryRepository
            .findByPlatformAndCode(mappingPlatform, mapping.getPlatformCategoryId())
            .orElseThrow(() -> new IllegalArgumentException("쿠팡 카테고리 목록에 없는 코드입니다."));

        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", request.parentId()));
        }

        Category category = Category.builder()
            .name(request.name())
            .platform(request.platform() == null ? null : Platform.from(request.platform()))
            .platformCategoryId(request.platformCategoryId())
            .parent(parent)
            .build();

        Category saved = categoryRepository.save(category);
        categoryMappingRepository.save(CategoryMapping.builder()
            .category(saved)
            .platform(mappingPlatform)
            .platformCategoryId(mapping.getPlatformCategoryId())
            .platformCategoryName(mapping.getPlatformCategoryName())
            .platformCategory(platformCategory)
            .build());
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
            .platform(request.platform() == null ? null : Platform.from(request.platform()))
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

    @Override
    public List<CategoryResponse> getCategoriesByPlatform(Platform platform) {
        return categoryRepository.findByPlatform(platform)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<CategoryTreeNode> browse(Long parentId) {
        List<Category> children = parentId == null
                ? categoryRepository.findByParentIsNull()
                : categoryRepository.findByParentId(parentId);
        return children.stream()
                .map(c -> new CategoryTreeNode(c.getId(), c.getName(),
                        !categoryRepository.existsByParentId(c.getId())))
                .sorted(Comparator.comparing(CategoryTreeNode::name))
                .toList();
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
            .id(category.getId())
            .name(category.getName())
            .platform(category.getPlatform() == null ? null : category.getPlatform().name())
            .platformCategoryId(category.getPlatformCategoryId())
            .parentId(category.getParent() != null ? category.getParent().getId() : null)
            .createdDate(category.getCreatedAt())
            .modifiedDate(category.getUpdatedAt())
            .build();
    }
}
