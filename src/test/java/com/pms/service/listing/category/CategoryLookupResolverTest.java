package com.pms.service.listing.category;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CategoryLookupResolver} (FEATURE_2608_06 / 45): resolves a registered platform, 400 for unsupported.
 */
class CategoryLookupResolverTest {

    @Test
    void resolve_coupang_returnsAdapter() {
        CategoryLookup coupang = mock(CategoryLookup.class);
        when(coupang.platform()).thenReturn("COUPANG");
        CategoryLookupResolver resolver = new CategoryLookupResolver(List.of(coupang));

        assertThat(resolver.resolve("COUPANG")).isSameAs(coupang);
    }

    @Test
    void resolve_unsupportedPlatform_throws400() {
        CategoryLookup coupang = mock(CategoryLookup.class);
        when(coupang.platform()).thenReturn("COUPANG");
        CategoryLookupResolver resolver = new CategoryLookupResolver(List.of(coupang));

        assertThatThrownBy(() -> resolver.resolve("NAVER"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
