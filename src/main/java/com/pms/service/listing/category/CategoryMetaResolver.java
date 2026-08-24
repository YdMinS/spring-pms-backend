package com.pms.service.listing.category;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link CategoryMetaAdapter} for a platform (FEATURE_2608_06 / 47). Mirrors
 * {@link CategoryLookupResolver}: Spring injects every {@link CategoryMetaAdapter} bean; {@link #resolve}
 * matches on {@link CategoryMetaAdapter#platform()}. Currently only COUPANG.
 */
@Component
public class CategoryMetaResolver {

    private final Map<String, CategoryMetaAdapter> byPlatform;

    public CategoryMetaResolver(List<CategoryMetaAdapter> adapters) {
        this.byPlatform = adapters.stream()
                .collect(Collectors.toMap(CategoryMetaAdapter::platform, Function.identity()));
    }

    /**
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching meta adapter
     * @throws IllegalArgumentException (→ 400) if no adapter handles the platform
     */
    public CategoryMetaAdapter resolve(String platform) {
        CategoryMetaAdapter adapter = byPlatform.get(platform);
        if (adapter == null) {
            throw new IllegalArgumentException("미지원 플랫폼: " + platform);
        }
        return adapter;
    }
}
