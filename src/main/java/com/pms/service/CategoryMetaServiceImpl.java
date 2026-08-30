package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.dto.response.CategoryMetaResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.service.listing.category.CategoryMetaResolver;
import com.pms.service.listing.category.CategoryMetaSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * {@link CategoryMetaService} implementation (FEATURE_2608_06 / 47). Thin: resolve the master (tenant-scoped),
 * the platform category code (via {@link MasterChannelConfigService}), and an active account, then delegate to
 * the {@link CategoryMetaResolver}. Values are stored on the master with no regeneration.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryMetaServiceImpl implements CategoryMetaService {

    private final MasterProductRepository masterProductRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final CategoryMetaResolver metaResolver;

    @Override
    public CategoryMetaSchema getSchema(Long categoryId, String platform) {
        // Schema is (platform × category)-dependent, not master-dependent — resolve the code from the category
        // id directly. Missing category / missing mapping → 400.
        String code = masterChannelConfigService.resolvePlatformCategoryCode(categoryId, platform);
        MarketplaceAccount account = resolveAccount(platform);
        return metaResolver.resolve(platform).getMeta(account, code);   // empty schema allowed
    }

    @Override
    public CategoryMetaResponse getMeta(Long masterId, String platform) {
        MasterProduct master = requireScopedMaster(masterId);
        Category standard = master.getCategory();
        if (standard == null) {
            throw new IllegalArgumentException("표준 카테고리 미설정");
        }
        // Reuse the category-scoped schema lookup, then layer the master's stored values on top.
        CategoryMetaSchema schema = getSchema(standard.getId(), platform);

        return CategoryMetaResponse.builder()
                .attributes(schema.attributes())
                .notices(schema.notices())
                .values(CategoryMetaResponse.Values.builder()
                        .attributes(master.getCategoryAttributes() != null
                                ? master.getCategoryAttributes() : Map.of())
                        .notices(master.getCategoryNotices() != null
                                ? master.getCategoryNotices() : Map.of())
                        .noticeGroup(master.getCategoryNoticeGroup())   // null = unset (no empty-string wrap)
                        .build())
                .build();
    }

    @Override
    @Transactional
    public void updateCategoryAttributes(Long masterId, Map<String, String> attributes,
                                         Map<String, String> notices, String noticeGroup) {
        MasterProduct master = requireScopedMaster(masterId);
        // No regeneration: attributes/notices are not thumbnail/detail binding keys.
        masterProductRepository.save(master.toBuilder()
                .categoryAttributes(attributes)
                .categoryNotices(notices)
                .categoryNoticeGroup(normalizeGroup(noticeGroup))
                .build());
    }

    /** Blank ({@code ""} / whitespace) is the same as "unset" — null is the signal the screen falls back on. */
    private static String normalizeGroup(String noticeGroup) {
        return noticeGroup == null || noticeGroup.isBlank() ? null : noticeGroup.trim();
    }

    private MasterProduct requireScopedMaster(Long id) {
        return masterProductRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", id));
    }

    /** Any active account for the platform (HMAC credentials); 400 if none (mirrors the lookup service). */
    private MarketplaceAccount resolveAccount(String platform) {
        return marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue(platform)
                .orElseThrow(() -> new IllegalArgumentException("활성 계정 없음"));
    }
}
