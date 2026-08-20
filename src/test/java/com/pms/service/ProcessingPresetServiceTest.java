package com.pms.service;

import com.pms.domain.ImageOp;
import com.pms.domain.ProcessingPreset;
import com.pms.dto.request.ProcessingPresetRequest;
import com.pms.dto.response.ProcessingPresetResponse;
import com.pms.repository.ProcessingPresetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ProcessingPreset business logic (FEATURE_2608_08): partial update (null = keep existing) + minimal
 * validateOperations. No default concept, so no demote logic. Mirror of {@code DetailTemplateServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProcessingPresetServiceTest {

    @Mock private ProcessingPresetRepository processingPresetRepository;

    @InjectMocks private ProcessingPresetServiceImpl service;

    private ImageOp overlay(String key) {
        return ImageOp.builder().type("overlay").assetStorageKey(key).build();
    }

    @Test
    void create_savesOperations() {
        given(processingPresetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ProcessingPresetRequest request = ProcessingPresetRequest.builder()
                .name("W").operations(List.of(overlay("wm.png"))).build();
        ProcessingPresetResponse response = service.create(request);

        assertThat(response.getName()).isEqualTo("W");
        assertThat(response.getOperations()).extracting(ImageOp::getAssetStorageKey).containsExactly("wm.png");
        assertThat(response.getActive()).isTrue(); // default true
    }

    @Test
    void update_nullOperations_keepsExisting() {
        ProcessingPreset existing = ProcessingPreset.builder()
                .id(1L).name("A").operations(List.of(overlay("wm.png"))).active(true).build();
        given(processingPresetRepository.findScopedById(1L)).willReturn(Optional.of(existing));
        given(processingPresetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // request.operations == null → keep-existing (partial update)
        ProcessingPresetRequest request = ProcessingPresetRequest.builder().name("B").build();
        service.update(1L, request);

        ArgumentCaptor<ProcessingPreset> captor = ArgumentCaptor.forClass(ProcessingPreset.class);
        verify(processingPresetRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("B");
        assertThat(captor.getValue().getOperations()).extracting(ImageOp::getAssetStorageKey)
                .containsExactly("wm.png"); // not overwritten
    }

    @Test
    void delete_removesPreset() {
        ProcessingPreset existing = ProcessingPreset.builder().id(1L).name("A").active(true).build();
        given(processingPresetRepository.findScopedById(1L)).willReturn(Optional.of(existing));

        service.delete(1L);

        verify(processingPresetRepository).delete(existing);
    }

    // ---- validateOperations: representative violations only ----

    @Test
    void create_opMissingType_throws() {
        ProcessingPresetRequest request = ProcessingPresetRequest.builder()
                .name("W").operations(List.of(ImageOp.builder().assetStorageKey("wm.png").build())).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        verify(processingPresetRepository, never()).save(any());
    }

    @Test
    void create_overlayMissingAssetKey_throws() {
        ProcessingPresetRequest request = ProcessingPresetRequest.builder()
                .name("W").operations(List.of(ImageOp.builder().type("overlay").build())).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assetStorageKey");
        verify(processingPresetRepository, never()).save(any());
    }
}
