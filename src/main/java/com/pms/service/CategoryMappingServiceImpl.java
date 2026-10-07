package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
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
    private final MasterProductRepository masterProductRepository;
    private final PlatformCategoryRepository platformCategoryRepository;

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

        Platform platform = Platform.from(request.getPlatform());
        // FEATURE_2610_05 / D38: a new and an updated mapping both link the platform_category row of
        // (platform, code) — price, category meta and settlement resolve the marketplace category through that FK.
        // A code missing from the list → 400 before anything is saved; saving an old FK-less mapping again fills it.
        PlatformCategory platformCategory = platformCategoryRepository
                .findByPlatformAndCode(platform, request.getPlatformCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("쿠팡 카테고리 목록에 없는 코드입니다."));
        CategoryMapping existing = categoryMappingRepository
                .findByCategoryIdAndPlatform(categoryId, platform).orElse(null);
        CategoryMapping toSave = existing != null
                ? existing.toBuilder()
                        .platformCategoryId(request.getPlatformCategoryId())
                        .platformCategoryName(request.getPlatformCategoryName())
                        .platformCategory(platformCategory)
                        .build()
                : CategoryMapping.builder()
                        .category(category)
                        .platform(platform)
                        .platformCategoryId(request.getPlatformCategoryId())
                        .platformCategoryName(request.getPlatformCategoryName())
                        .platformCategory(platformCategory)
                        .build();
        CategoryMapping saved = categoryMappingRepository.save(toSave);

        // Best-effort commission prefill (46): seed a default COUPANG rate when absent. Runs in its own
        // REQUIRES_NEW transaction; swallow any failure so the mapping upsert still succeeds.
        try {
            commissionPrefillService.prefillIfAbsent(
                    categoryId, platform, request.getPlatformCategoryName());
        } catch (Exception e) {
            log.warn("Commission prefill failed for category {} platform {} (mapping saved anyway): {}",
                    categoryId, platform, e.getMessage());
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMapping(Long categoryId, Platform platform) {
        CategoryMapping existing = categoryMappingRepository
                .findByCategoryIdAndPlatform(categoryId, platform)
                .orElseThrow(() -> new BusinessException(
                        "CategoryMapping not found for platform: " + platform, HttpStatus.NOT_FOUND));
        // FEATURE_2610_05 / D33: a standard category that a master uses keeps at least one platform mapping —
        // otherwise that master would hold a category no marketplace can resolve. Masters of every tenant count.
        if (categoryMappingRepository.countByCategoryId(categoryId) == 1
                && masterProductRepository.countAllTenantsByCategoryId(categoryId) > 0) {
            throw new IllegalArgumentException("이 카테고리를 쓰는 마스터가 있어 마지막 연결은 지울 수 없습니다.");
        }
        categoryMappingRepository.delete(existing);
    }

    private CategoryMappingResponse toResponse(CategoryMapping mapping) {
        return CategoryMappingResponse.builder()
                .platform(mapping.getPlatform().name())
                .platformCategoryId(mapping.getPlatformCategoryId())
                .platformCategoryName(mapping.getPlatformCategoryName())
                .build();
    }
}
