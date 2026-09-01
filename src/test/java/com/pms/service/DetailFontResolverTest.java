package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Resolver unit test: tenant isolation and CSS-injection defence are MUST-KEEP here, since this is the
 * only place that decides what reaches the inline styles / @font-face of a generated detail page.
 */
@ExtendWith(MockitoExtension.class)
class DetailFontResolverTest {

    private static final String STACK = "'Nanum Gothic',sans-serif";

    @Mock private FontAssetRepository fontAssetRepository;
    @InjectMocks private DetailFontResolver resolver;

    private void fontFaceEnabled(boolean enabled) {
        ReflectionTestUtils.setField(resolver, "fontFaceEnabled", enabled);
    }

    private FontAsset font(Long id, FontSource source, String storageKey, String webUrl, String webStack) {
        return FontAsset.builder()
                .id(id).tenantId(1L).displayName("f").familyKey("F")
                .source(source).storageKey(storageKey).webUrl(webUrl).webStack(webStack)
                .build();
    }

    private List<DetailBlock> blocksReferencing(String rawValue) {
        return List.of(DetailBlock.builder()
                .type("text").bind("brandName").textStyle(Map.of("fontFamily", rawValue)).build());
    }

    @Test
    void uploadedFontWithWebUrl_fillsSrcUrlAndPrefixedFamily() {
        fontFaceEnabled(true);
        given(fontAssetRepository.findSystemAndTenant(any()))
                .willReturn(List.of(font(12L, FontSource.UPLOADED, "disk/f.ttf", "https://cdn/f.ttf", STACK)));

        DetailFont resolved = resolver.resolve(blocksReferencing("12")).get("12");

        assertThat(resolved.srcUrl()).isEqualTo("https://cdn/f.ttf");
        assertThat(resolved.format()).isEqualTo("truetype");
        assertThat(resolved.family()).isEqualTo("'oclyx-font-12'," + STACK);
    }

    @Test
    void bundledFontWithoutWebUrl_hasNoSrcAndFallsBackToStack() {
        fontFaceEnabled(true);
        given(fontAssetRepository.findSystemAndTenant(any()))
                .willReturn(List.of(font(1L, FontSource.BUNDLED, "fonts/system-sans.ttf", null, STACK)));

        DetailFont resolved = resolver.resolve(blocksReferencing("1")).get("1");

        assertThat(resolved.srcUrl()).isNull();
        assertThat(resolved.family()).isEqualTo(STACK);
    }

    @Test
    void legacyUploadedRow_withHttpStorageKeyOnly_stillResolvesSrcUrl() {
        fontFaceEnabled(true);
        given(fontAssetRepository.findSystemAndTenant(any()))
                .willReturn(List.of(font(7L, FontSource.UPLOADED, "https://cdn/legacy.otf", null, null)));

        DetailFont resolved = resolver.resolve(blocksReferencing("7")).get("7");

        assertThat(resolved.srcUrl()).isEqualTo("https://cdn/legacy.otf");
        assertThat(resolved.format()).isEqualTo("opentype");
        assertThat(resolved.family()).isEqualTo("'oclyx-font-7',sans-serif");
    }

    @Test
    void fontOfAnotherTenant_isDropped() {
        // findSystemAndTenant is the isolation boundary: an id it does not return must not resolve.
        given(fontAssetRepository.findSystemAndTenant(any()))
                .willReturn(List.of(font(1L, FontSource.BUNDLED, "fonts/a.ttf", null, STACK)));

        assertThat(resolver.resolve(blocksReferencing("99"))).isEmpty();
    }

    @Test
    void injectionAttempts_inUrlAndStack_areDropped() {
        fontFaceEnabled(true);
        given(fontAssetRepository.findSystemAndTenant(any())).willReturn(List.of(
                font(1L, FontSource.UPLOADED, "https://x/f.ttf');}</style><script>", null, null),
                font(2L, FontSource.BUNDLED, "fonts/a.ttf", null, "a;color:red")));

        Map<String, DetailFont> resolved = resolver.resolve(List.of(
                DetailBlock.builder().type("text").textStyle(Map.of("fontFamily", "1")).build(),
                DetailBlock.builder().type("text").textStyle(Map.of("fontFamily", "2")).build()));

        assertThat(resolved).isEmpty(); // url dropped → nothing left; stack dropped → nothing left
    }

    @Test
    void nonNumericStyleValue_isIgnoredWithoutError() {
        assertThat(resolver.resolve(blocksReferencing("gothic"))).isEmpty();
    }

    @Test
    void killSwitchOff_dropsSrcUrlButKeepsStack() {
        fontFaceEnabled(false);
        given(fontAssetRepository.findSystemAndTenant(any()))
                .willReturn(List.of(font(12L, FontSource.UPLOADED, "disk/f.ttf", "https://cdn/f.ttf", STACK)));

        DetailFont resolved = resolver.resolve(blocksReferencing("12")).get("12");

        assertThat(resolved.srcUrl()).isNull();
        assertThat(resolved.family()).isEqualTo(STACK);
    }
}
