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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Seeder unit test: promotion must be idempotent and must never block application startup. */
@ExtendWith(MockitoExtension.class)
class SystemFontSeederTest {

    private static final String PUBLIC_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/tenants/_system/fonts/system-sans.ttf";

    @Mock private FontAssetRepository fontAssetRepository;
    @Mock private ImageStorageService imageStorageService;
    @InjectMocks private SystemFontSeeder seeder;

    private FontAsset seeded(String webUrl) {
        return FontAsset.builder()
                .id(1L).tenantId(null).displayName("System Sans").familyKey("SansSerif")
                .source(FontSource.BUNDLED).storageKey("fonts/system-sans.ttf").webUrl(webUrl)
                .build();
    }

    @Test
    void freshSeed_setsWebStackAndPromotesToPublicUrl() {
        given(fontAssetRepository.findByFamilyKeyAndTenantIdIsNull(anyString())).willReturn(Optional.empty());
        given(fontAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageStorageService.uploadShared(any(), any(), any(), any())).willReturn(PUBLIC_URL);

        seeder.run(null);

        ArgumentCaptor<FontAsset> captor = ArgumentCaptor.forClass(FontAsset.class);
        verify(fontAssetRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getWebStack()).contains("Nanum Gothic");
        assertThat(captor.getAllValues().get(1).getWebUrl()).isEqualTo(PUBLIC_URL);
        // storageKey must stay a classpath path, or FontRegistry can no longer load the binary.
        assertThat(captor.getAllValues().get(1).getStorageKey()).isEqualTo("fonts/system-sans.ttf");
        verify(imageStorageService).uploadShared(any(), any(), any(), any());
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
}
