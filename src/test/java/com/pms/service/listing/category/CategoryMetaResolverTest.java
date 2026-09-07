package com.pms.service.listing.category;

import com.pms.domain.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CategoryMetaResolver} (FEATURE_2608_06 / 47): resolves a registered platform, 400 for unsupported.
 */
class CategoryMetaResolverTest {

    @Test
    void resolve_coupang_returnsAdapter() {
        CategoryMetaAdapter coupang = mock(CategoryMetaAdapter.class);
        when(coupang.platform()).thenReturn(Platform.COUPANG);
        CategoryMetaResolver resolver = new CategoryMetaResolver(List.of(coupang));

        assertThat(resolver.resolve(Platform.COUPANG)).isSameAs(coupang);
    }

    @Test
    void resolve_unsupportedPlatform_throws400() {
        CategoryMetaAdapter coupang = mock(CategoryMetaAdapter.class);
        when(coupang.platform()).thenReturn(Platform.COUPANG);
        CategoryMetaResolver resolver = new CategoryMetaResolver(List.of(coupang));

        assertThatThrownBy(() -> resolver.resolve(Platform.NAVER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
