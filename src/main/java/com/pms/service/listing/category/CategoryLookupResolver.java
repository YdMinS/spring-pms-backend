package com.pms.service.listing.category;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link CategoryLookup} adapter for a platform (FEATURE_2608_06 / 45). Mirrors
 * {@code ListingChannelResolver}: Spring injects every {@link CategoryLookup} bean; {@link #resolve} matches on
 * {@link CategoryLookup#platform()}. Currently only COUPANG; the NAVER adapter joins later with no change here.
 */
@Component
public class CategoryLookupResolver {

    private final Map<String, CategoryLookup> byPlatform;

    public CategoryLookupResolver(List<CategoryLookup> lookups) {
        this.byPlatform = lookups.stream()
                .collect(Collectors.toMap(CategoryLookup::platform, Function.identity()));
    }

    /**
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching lookup adapter
     * @throws IllegalArgumentException (→ 400) if no adapter handles the platform
     */
    public CategoryLookup resolve(String platform) {
        CategoryLookup lookup = byPlatform.get(platform);
        if (lookup == null) {
            throw new IllegalArgumentException("미지원 플랫폼: " + platform);
        }
        return lookup;
    }
}
