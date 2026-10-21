package com.pms.service.listing.category;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * ESM {@link CategoryLookup} (FEATURE_2610_26 / D11 · D12) — browses the imported ESM list in
 * {@code platform_category}; no ESM call and no marketplace account. Same shape as {@link ElevenstCategoryLookup}:
 * an intermediate row has no code, so its drill-down key is {@code "id:" + platform_category.id}; a leaf carries its
 * ESM code. Product-name prediction is not offered.
 */
@Component
@RequiredArgsConstructor
public class EsmCategoryLookup implements CategoryLookup {

    static final String NODE_KEY_PREFIX = "id:";

    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    public Platform platform() {
        return Platform.ESM;
    }

    @Override
    public boolean requiresAccount() {
        return false;
    }

    @Override
    public List<CategoryNode> browse(MarketplaceAccount account, String parentCode) {
        List<PlatformCategory> children;
        if (!StringUtils.hasText(parentCode)) {
            children = platformCategoryRepository.findByParentIsNullAndPlatform(Platform.ESM);
        } else if (parentCode.startsWith(NODE_KEY_PREFIX)
                && parentCode.substring(NODE_KEY_PREFIX.length()).matches("\\d+")) {
            Long parentId = Long.valueOf(parentCode.substring(NODE_KEY_PREFIX.length()));
            children = platformCategoryRepository.findByParentId(parentId).stream()
                    .filter(pc -> pc.getPlatform() == Platform.ESM)
                    .toList();
        } else {
            throw new IllegalArgumentException("G마켓 · 옥션 카테고리 위치 값이 올바르지 않습니다: " + parentCode);
        }
        return children.stream()
                .sorted(Comparator.comparing(PlatformCategory::getId))
                .map(pc -> pc.getCode() != null
                        ? new CategoryNode(pc.getCode(), pc.getName(), true)
                        : new CategoryNode(NODE_KEY_PREFIX + pc.getId(), pc.getName(), false))
                .toList();
    }

    @Override
    public List<CategorySuggestion> predict(MarketplaceAccount account, String productName) {
        throw new IllegalArgumentException("G마켓 · 옥션은 상품명 추천을 지원하지 않습니다.");
    }
}
