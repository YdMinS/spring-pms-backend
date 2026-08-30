package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default {@link CategoryMappingService}. Standard-category × platform → marketplace code CRUD (44).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryMappingServiceImpl implements CategoryMappingService {

    private final CategoryMappingRepository categoryMappingRepository;
    private final CategoryRepository categoryRepository;
    private final CommissionPrefillService commissionPrefillService;

    @Override
    public List<CategoryMappingResponse> getMappings(Long categoryId) {
        return categoryMappingRepository.findByCategoryId(categoryId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CategoryMappingResponse upsertMapping(Long categoryId, CategoryMappingRequest request) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));

        CategoryMapping existing = categoryMappingRepository
                .findByCategoryIdAndPlatform(categoryId, request.getPlatform()).orElse(null);
        CategoryMapping toSave = existing != null
                ? existing.toBuilder()
                        .platformCategoryId(request.getPlatformCategoryId())
                        .platformCategoryName(request.getPlatformCategoryName())
                        .build()
                : CategoryMapping.builder()
                        .category(category)
                        .platform(request.getPlatform())
                        .platformCategoryId(request.getPlatformCategoryId())
                        .platformCategoryName(request.getPlatformCategoryName())
                        .build();
        CategoryMapping saved = categoryMappingRepository.save(toSave);

        // Best-effort commission prefill (46): seed a default COUPANG rate when absent. Runs in its own
        // REQUIRES_NEW transaction; swallow any failure so the mapping upsert still succeeds.
        try {
            commissionPrefillService.prefillIfAbsent(
                    categoryId, request.getPlatform(), request.getPlatformCategoryName());
        } catch (Exception e) {
            log.warn("Commission prefill failed for category {} platform {} (mapping saved anyway): {}",
                    categoryId, request.getPlatform(), e.getMessage());
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMapping(Long categoryId, String platform) {
        CategoryMapping existing = categoryMappingRepository
                .findByCategoryIdAndPlatform(categoryId, platform)
                .orElseThrow(() -> new BusinessException(
                        "CategoryMapping not found for platform: " + platform, HttpStatus.NOT_FOUND));
        categoryMappingRepository.delete(existing);
    }

    private CategoryMappingResponse toResponse(CategoryMapping mapping) {
        return CategoryMappingResponse.builder()
                .platform(mapping.getPlatform())
                .platformCategoryId(mapping.getPlatformCategoryId())
                .platformCategoryName(mapping.getPlatformCategoryName())
                .build();
    }
}
