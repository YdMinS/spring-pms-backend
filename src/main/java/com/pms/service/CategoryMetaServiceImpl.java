package com.pms.service;

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
    public CategoryMetaResponse getMeta(Long masterId, String platform) {
        MasterProduct master = requireScopedMaster(masterId);
        String code = masterChannelConfigService.resolvePlatformCategoryCode(master, platform);   // 400 if unset
        MarketplaceAccount account = resolveAccount(platform);
        CategoryMetaSchema schema = metaResolver.resolve(platform).getMeta(account, code);   // empty allowed

        return CategoryMetaResponse.builder()
                .attributes(schema.attributes())
                .notices(schema.notices())
                .values(CategoryMetaResponse.Values.builder()
                        .attributes(master.getCategoryAttributes() != null
                                ? master.getCategoryAttributes() : Map.of())
                        .notices(master.getCategoryNotices() != null
                                ? master.getCategoryNotices() : Map.of())
                        .build())
                .build();
    }

    @Override
    @Transactional
    public void updateCategoryAttributes(Long masterId, Map<String, String> attributes,
                                         Map<String, String> notices) {
        MasterProduct master = requireScopedMaster(masterId);
        // No regeneration: attributes/notices are not thumbnail/detail binding keys.
        masterProductRepository.save(master.toBuilder()
                .categoryAttributes(attributes)
                .categoryNotices(notices)
                .build());
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
