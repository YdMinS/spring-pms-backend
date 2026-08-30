package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.listing.category.CategoryLookupResolver;
import com.pms.service.listing.category.CategoryNode;
import com.pms.service.listing.category.CategorySuggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * {@link CategoryLookupService} implementation (FEATURE_2608_06 / 45). Delegation-only layer: resolve the
 * account, then call the resolved {@code CategoryLookup} adapter.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryLookupServiceImpl implements CategoryLookupService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CategoryLookupResolver resolver;

    @Override
    public List<CategoryNode> browse(String platform, String parentCode, Long sellerId) {
        MarketplaceAccount account = resolveAccount(platform, sellerId);
        return resolver.resolve(platform).browse(account, parentCode);
    }

    @Override
    public List<CategorySuggestion> predict(String platform, String productName, Long sellerId) {
        if (!StringUtils.hasText(productName)) {
            throw new IllegalArgumentException("productName 필수");
        }
        MarketplaceAccount account = resolveAccount(platform, sellerId);
        return resolver.resolve(platform).predict(account, productName);
    }

    /**
     * Resolve the marketplace account for the lookup call. sellerId present → the (seller, platform) account
     * (404 if none, 400 if inactive); absent → any active account for the platform (400 if none).
     */
    private MarketplaceAccount resolveAccount(String platform, Long sellerId) {
        if (sellerId != null) {
            MarketplaceAccount account = marketplaceAccountRepository
                    .findBySeller_IdAndPlatform(sellerId, platform)
                    .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", sellerId));
            if (Boolean.FALSE.equals(account.getIsActive())) {
                throw new IllegalArgumentException("비활성 계정");
            }
            return account;
        }
        return marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue(platform)
                .orElseThrow(() -> new IllegalArgumentException("활성 계정 없음"));
    }
}
