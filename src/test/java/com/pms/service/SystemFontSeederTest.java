package com.pms.service;

import com.pms.domain.FontAsset;
import com.pms.domain.FontSource;
import com.pms.repository.FontAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.awt.Font;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Seeder unit test: every declared font must be bundled, promotion must be idempotent, and neither a
 * storage failure nor a single bad font may block application startup.
 */
@ExtendWith(MockitoExtension.class)
class SystemFontSeederTest {

    private static final String PUBLIC_URL =
            "https://bucket.s3.ap-northeast-2.amazonaws.com/tenants/_system/fonts/system-sans.ttf";
    private static final String EXISTING_STACK = "'Nanum Gothic','Malgun Gothic','Apple SD Gothic Neo',sans-serif";

    @Mock private FontAssetRepository fontAssetRepository;
    @Mock private ImageStorageService imageStorageService;
    @InjectMocks private SystemFontSeeder seeder;

    /** A row as it exists after a previous boot: webStack already filled. */
    private FontAsset seeded(String webUrl) {
        return seeded(webUrl, EXISTING_STACK);
    }

    private FontAsset seeded(String webUrl, String webStack) {
        return FontAsset.builder()
                .id(1L).tenantId(null).displayName("System Sans").familyKey("SansSerif")
                .source(FontSource.BUNDLED).storageKey("fonts/system-sans.ttf")
                .webStack(webStack).webUrl(webUrl)
                .build();
    }

    /**
     * Guards the one thing no mock can: that every font declared in SYSTEM_FONTS is actually bundled and
     * is a real TrueType binary. Catches a missing file after a rename, and an .otf renamed to .ttf.
     */
    @Test
    void everyDeclaredFontBinaryIsBundledAndLoadable() throws Exception {
        for (SystemFontSeeder.SystemFont def : SystemFontSeeder.SYSTEM_FONTS) {
            ClassPathResource resource = new ClassPathResource(def.classpathKey());
            assertThat(resource.exists())
                    .as("bundled binary for %s (%s)", def.familyKey(), def.classpathKey())
                    .isTrue();
            try (InputStream in = resource.getInputStream()) {
                assertThat(Font.createFont(Font.TRUETYPE_FONT, in))
                        .as("loadable TrueType font: %s", def.classpathKey())
                        .isNotNull();
            }
        }
    }

    @Test
    void seedsEveryDeclaredFont_whenNoneExist() {
        int fonts = SystemFontSeeder.SYSTEM_FONTS.size();
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString())).willReturn(Optional.empty());
        given(fontAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageStorageService.uploadShared(any(), any(), any(), any())).willReturn(PUBLIC_URL);

        seeder.run(null);

        // insert + webUrl patch, per font.
        ArgumentCaptor<FontAsset> captor = ArgumentCaptor.forClass(FontAsset.class);
        verify(fontAssetRepository, times(fonts * 2)).save(captor.capture());
        verify(imageStorageService, times(fonts)).uploadShared(any(), any(), any(), any());

        List<FontAsset> inserted = captor.getAllValues().stream().filter(f -> f.getWebUrl() == null).toList();
        assertThat(inserted).hasSize(fonts);
        assertThat(inserted).allSatisfy(f -> {
            assertThat(f.getTenantId()).isNull();                       // system-shared
            assertThat(f.getSource()).isEqualTo(FontSource.BUNDLED);
            assertThat(f.getWebStack()).isNotBlank();
            assertThat(f.getStorageKey()).startsWith("fonts/");          // classpath path, not an S3 URL
        });
        assertThat(inserted).extracting(FontAsset::getFamilyKey)
                .containsExactlyElementsOf(SystemFontSeeder.SYSTEM_FONTS.stream()
                        .map(SystemFontSeeder.SystemFont::familyKey).toList());
    }

    /** Each font is uploaded under its own filename, or they would all overwrite one S3 object. */
    @Test
    void eachFontIsUploadedUnderItsOwnFilename() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString())).willReturn(Optional.empty());
        given(fontAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageStorageService.uploadShared(any(), any(), any(), any())).willReturn(PUBLIC_URL);

        seeder.run(null);

        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService, atLeastOnce())
                .uploadShared(any(), eq("fonts"), filename.capture(), eq("font/ttf"));
        assertThat(filename.getAllValues())
                .containsExactlyElementsOf(SystemFontSeeder.SYSTEM_FONTS.stream()
                        .map(f -> f.classpathKey().substring("fonts/".length())).toList());
    }

    /** Rows created before web_stack existed (dev id=1) must be backfilled. */
    @Test
    void existingRowWithBlankWebStack_isBackfilled() {
        // General stub first: a later anyString() would override the specific one (last match wins).
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString())).willReturn(Optional.empty());
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull("SansSerif"))
                .willReturn(Optional.of(seeded(PUBLIC_URL, null)));
        given(fontAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageStorageService.uploadShared(any(), any(), any(), any())).willReturn(PUBLIC_URL);

        seeder.run(null);

        ArgumentCaptor<FontAsset> captor = ArgumentCaptor.forClass(FontAsset.class);
        verify(fontAssetRepository, atLeastOnce()).save(captor.capture());
        FontAsset patched = captor.getAllValues().stream()
                .filter(f -> "SansSerif".equals(f.getFamilyKey()))
                .findFirst().orElseThrow();
        assertThat(patched.getWebStack()).contains("Nanum Gothic");
        assertThat(patched.getStorageKey()).isEqualTo("fonts/system-sans.ttf"); // never rewritten to a URL
        assertThat(patched.getWebUrl()).isEqualTo(PUBLIC_URL);                  // not re-uploaded
    }

    /** An operator-set stack is not clobbered by the declared default. */
    @Test
    void existingRowWithWebStack_isNotOverwritten() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString()))
                .willReturn(Optional.of(seeded(PUBLIC_URL, "'Custom',sans-serif")));

        seeder.run(null);

        verify(fontAssetRepository, never()).save(any());
        verify(imageStorageService, never()).uploadShared(any(), any(), any(), any());
    }

    @Test
    void rowThatAlreadyHasWebUrl_isNotUploadedAgain() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString()))
                .willReturn(Optional.of(seeded(PUBLIC_URL)));

        seeder.run(null);

        verify(imageStorageService, never()).uploadShared(any(), any(), any(), any());
        verify(fontAssetRepository, never()).save(any());
    }

    @Test
    void nonPublicStoredValue_isNotPersisted() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString()))
                .willReturn(Optional.of(seeded(null)));
        given(imageStorageService.uploadShared(any(), any(), any(), any()))
                .willReturn("/app/uploads/products/_system/fonts/system-sans.ttf");

        seeder.run(null);

        verify(fontAssetRepository, never()).save(any());
    }

    @Test
    void uploadFailure_doesNotBlockStartup() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString()))
                .willReturn(Optional.of(seeded(null)));
        willThrow(new IllegalStateException("S3 down"))
                .given(imageStorageService).uploadShared(any(), any(), any(), any());

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
        verify(fontAssetRepository, never()).save(any());
    }

    /** One font failing must not cost the others: the loop keeps going. */
    @Test
    void uploadFailureOnOneFont_doesNotStopTheRest() {
        int fonts = SystemFontSeeder.SYSTEM_FONTS.size();
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString())).willReturn(Optional.empty());
        given(fontAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageStorageService.uploadShared(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("S3 down"))  // first font
                .willReturn(PUBLIC_URL);                          // the rest

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();

        // every font still inserted; only the failed one has no webUrl patch
        verify(fontAssetRepository, times(fonts * 2 - 1)).save(any());
        verify(imageStorageService, times(fonts)).uploadShared(any(), any(), any(), any());
    }
}
