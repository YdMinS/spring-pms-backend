package com.pms.service;

import com.pms.domain.MasterPlatformCategory;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.MasterPlatformCategoryRequest;
import com.pms.dto.response.MasterPlatformCategoryResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterPlatformCategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default {@link MasterPlatformCategoryService} (FEATURE_2610_12 / D40). Same validation as the standard
 * category mapping upsert ({@code CategoryMappingServiceImpl}): the code must be in the platform's list.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterPlatformCategoryServiceImpl implements MasterPlatformCategoryService {

    private final MasterProductRepository masterProductRepository;
    private final MasterPlatformCategoryRepository masterPlatformCategoryRepository;
    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    public List<MasterPlatformCategoryResponse> getAll(Long masterId) {
        requireScopedMaster(masterId);
        return masterPlatformCategoryRepository.findByMasterProductId(masterId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public MasterPlatformCategoryResponse upsert(Long masterId, MasterPlatformCategoryRequest request) {
        MasterProduct master = requireScopedMaster(masterId);
        Platform platform = Platform.from(request.getPlatform());
        // Same rule and texts as the standard category mapping (2610_05 / D38 · 2610_10 / D21 ④).
        PlatformCategory platformCategory = platformCategoryRepository
                .findByPlatformAndCode(platform, request.getPlatformCategoryId())
                .orElseThrow(() -> new IllegalArgumentException(platform == Platform.ELEVENST
                        ? "11번가 카테고리 목록에 없는 코드입니다."
                        : "쿠팡 카테고리 목록에 없는 코드입니다."));
        MasterPlatformCategory existing = masterPlatformCategoryRepository
                .findByMasterProductIdAndPlatform(masterId, platform).orElse(null);
        MasterPlatformCategory toSave = existing != null
                ? existing.toBuilder()
                        .platformCategory(platformCategory)
                        .platformCategoryName(request.getPlatformCategoryName())
                        .build()
                : MasterPlatformCategory.builder()
                        .masterProduct(master)
                        .platform(platform)
                        .platformCategory(platformCategory)
                        .platformCategoryName(request.getPlatformCategoryName())
                        .build();
        return toResponse(masterPlatformCategoryRepository.save(toSave));
    }

    @Override
    @Transactional
    public void delete(Long masterId, Platform platform) {
        requireScopedMaster(masterId);
        MasterPlatformCategory existing = masterPlatformCategoryRepository
                .findByMasterProductIdAndPlatform(masterId, platform)
                .orElseThrow(() -> new BusinessException(
                        "MasterPlatformCategory not found for platform: " + platform, HttpStatus.NOT_FOUND));
        masterPlatformCategoryRepository.delete(existing);
    }

    private MasterProduct requireScopedMaster(Long id) {
        return masterProductRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", id));
    }

    // ⚠️ LAZY platformCategory — callers run inside this class's transaction.
    private MasterPlatformCategoryResponse toResponse(MasterPlatformCategory row) {
        return MasterPlatformCategoryResponse.builder()
                .platform(row.getPlatform().name())
                .platformCategoryId(row.getPlatformCategory().getCode())
                .platformCategoryName(row.getPlatformCategoryName())
                .build();
    }
}
