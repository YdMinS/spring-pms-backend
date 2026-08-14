package com.pms.service;

import com.pms.domain.TemplateAsset;
import com.pms.dto.response.TemplateAssetResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.TemplateAssetRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Template asset service logic: upload (validator + uploadBytes + save), empty-file guard, and the
 * delete cross-tenant guard (the security branch — PK findById is not tenant-filtered by @TenantId).
 */
@ExtendWith(MockitoExtension.class)
class TemplateAssetServiceTest {

    private static final Long TENANT_1 = 1L;

    @Mock private TemplateAssetRepository templateAssetRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private TemplateAssetServiceImpl service;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void upload_validatesStoresAndSaves() {
        TenantContext.set(TENANT_1);
        MockMultipartFile file = new MockMultipartFile(
                "file", "무료배송.png", "image/png", new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), eq("thumbnail-assets"), anyString(), eq("image/png")))
                .willReturn("thumbnail-assets/asset_1.png");
        given(templateAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        TemplateAssetResponse response = service.upload(file);

        verify(imageValidator).validate(file);
        ArgumentCaptor<TemplateAsset> captor = ArgumentCaptor.forClass(TemplateAsset.class);
        verify(templateAssetRepository).save(captor.capture());
        TemplateAsset saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("무료배송");                    // extension stripped
        assertThat(saved.getStorageKey()).isEqualTo("thumbnail-assets/asset_1.png");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(response.getStorageKey()).isEqualTo("thumbnail-assets/asset_1.png");
    }

    @Test
    void upload_emptyFile_throws400() {
        MockMultipartFile empty = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(empty))
                .isInstanceOf(IllegalArgumentException.class);
        verify(templateAssetRepository, never()).save(any());
    }

    @Test
    void rename_ownTenant_updatesName() {
        TenantContext.set(TENANT_1);
        TemplateAsset asset = TemplateAsset.builder()
                .id(5L).tenantId(TENANT_1).name("old").storageKey("thumbnail-assets/a.png").build();
        given(templateAssetRepository.findById(5L)).willReturn(Optional.of(asset));
        given(templateAssetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        TemplateAssetResponse response = service.rename(5L, "무료배송 배지");

        ArgumentCaptor<TemplateAsset> captor = ArgumentCaptor.forClass(TemplateAsset.class);
        verify(templateAssetRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("무료배송 배지");
        assertThat(captor.getValue().getStorageKey()).isEqualTo("thumbnail-assets/a.png"); // key unchanged
        assertThat(response.getName()).isEqualTo("무료배송 배지");
    }

    @Test
    void rename_blankName_throws400() {
        assertThatThrownBy(() -> service.rename(5L, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(templateAssetRepository, never()).save(any());
    }

    @Test
    void rename_otherTenant_throwsNotFound() {
        TenantContext.set(TENANT_1);
        TemplateAsset otherTenants = TemplateAsset.builder()
                .id(9L).tenantId(2L).name("b").storageKey("thumbnail-assets/b.png").build();
        given(templateAssetRepository.findById(9L)).willReturn(Optional.of(otherTenants));

        assertThatThrownBy(() -> service.rename(9L, "new"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(templateAssetRepository, never()).save(any());
    }

    @Test
    void delete_ownTenant_removesStorageAndRow() {
        TenantContext.set(TENANT_1);
        TemplateAsset asset = TemplateAsset.builder()
                .id(7L).tenantId(TENANT_1).name("a").storageKey("thumbnail-assets/a.png").build();
        given(templateAssetRepository.findById(7L)).willReturn(Optional.of(asset));

        service.delete(7L);

        verify(imageStorageService).deleteImage("thumbnail-assets/a.png");
        verify(templateAssetRepository).delete(asset);
    }

    @Test
    void delete_otherTenant_throwsNotFound() {
        TenantContext.set(TENANT_1);
        TemplateAsset otherTenants = TemplateAsset.builder()
                .id(8L).tenantId(2L).name("b").storageKey("thumbnail-assets/b.png").build();
        given(templateAssetRepository.findById(8L)).willReturn(Optional.of(otherTenants));

        assertThatThrownBy(() -> service.delete(8L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(imageStorageService, never()).deleteImage(any());
        verify(templateAssetRepository, never()).delete(any());
    }
}
