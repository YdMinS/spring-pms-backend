package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.service.listing.category.CategoryLookup;
import com.pms.service.listing.category.CategoryLookupResolver;
import com.pms.service.listing.category.CategoryNode;
import com.pms.service.listing.category.CategorySuggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * {@link CategoryLookupService} implementation (FEATURE_2608_06 / 45). Delegation-only layer: resolve the
 * {@code CategoryLookup} adapter, resolve the account only when the adapter requires one (11st does not —
 * FEATURE_2610_10 / D23), then call the adapter. Name search reads {@code platform_category} directly.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryLookupServiceImpl implements CategoryLookupService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CategoryLookupResolver resolver;
    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    public List<CategoryNode> browse(Platform platform, String parentCode, Long sellerId) {
        CategoryLookup lookup = resolver.resolve(platform);
        MarketplaceAccount account = lookup.requiresAccount() ? resolveAccount(platform, sellerId) : null;
        return lookup.browse(account, parentCode);
    }

    @Override
    public List<CategorySuggestion> predict(Platform platform, String productName, Long sellerId) {
        if (!StringUtils.hasText(productName)) {
            throw new IllegalArgumentException("productName 필수");
        }
        CategoryLookup lookup = resolver.resolve(platform);
        MarketplaceAccount account = lookup.requiresAccount() ? resolveAccount(platform, sellerId) : null;
        return lookup.predict(account, productName);
    }

    @Override
    public List<CategorySuggestion> search(Platform platform, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("검색어를 입력하세요.");
        }
        return platformCategoryRepository
                .findTop50ByPlatformAndCodeIsNotNullAndNameContainingOrderByNameAsc(platform, keyword.trim())
                .stream()
                .map(pc -> new CategorySuggestion(pc.getCode(), pc.getName(), namePathOf(pc)))
                .toList();
    }

    /** Root → leaf names joined with " > " (parents are lazy; the read-only transaction keeps them loadable). */
    private static String namePathOf(PlatformCategory leaf) {
        Deque<String> names = new ArrayDeque<>();
        for (PlatformCategory node = leaf; node != null; node = node.getParent()) {
            names.addFirst(node.getName());
        }
        return String.join(" > ", names);
    }

    /**
     * Resolve the marketplace account for the lookup call. sellerId present → the (seller, platform) account
     * (404 if none, 400 if inactive); absent → any active account for the platform (400 if none).
     */
    private MarketplaceAccount resolveAccount(Platform platform, Long sellerId) {
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
