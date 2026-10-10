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
 * 11st {@link CategoryLookup} (FEATURE_2610_10 / D21 ③ · D23) — browses the imported 11st list in
 * {@code platform_category}; no 11st call and no marketplace account.
 *
 * <p>Intermediate rows have no code (D24), so the drill-down key of an intermediate node is
 * {@code "id:" + platform_category.id}; a leaf node carries its 11st code, which the mapping save looks up
 * with {@code findByPlatformAndCode}. Children are listed in id order (= the 11st order of the first import).
 * Product-name prediction is not offered (D21 ③).</p>
 */
@Component
@RequiredArgsConstructor
public class ElevenstCategoryLookup implements CategoryLookup {

    static final String NODE_KEY_PREFIX = "id:";

    private final PlatformCategoryRepository platformCategoryRepository;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public boolean requiresAccount() {
        return false;
    }

    @Override
    public List<CategoryNode> browse(MarketplaceAccount account, String parentCode) {
        List<PlatformCategory> children;
        if (!StringUtils.hasText(parentCode)) {
            children = platformCategoryRepository.findByParentIsNullAndPlatform(Platform.ELEVENST);
        } else if (parentCode.startsWith(NODE_KEY_PREFIX)
                && parentCode.substring(NODE_KEY_PREFIX.length()).matches("\\d+")) {
            Long parentId = Long.valueOf(parentCode.substring(NODE_KEY_PREFIX.length()));
            children = platformCategoryRepository.findByParentId(parentId).stream()
                    .filter(pc -> pc.getPlatform() == Platform.ELEVENST)
                    .toList();
        } else {
            throw new IllegalArgumentException("11번가 카테고리 위치 값이 올바르지 않습니다: " + parentCode);
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
        throw new IllegalArgumentException("11번가는 상품명 추천을 지원하지 않습니다.");
    }
}
