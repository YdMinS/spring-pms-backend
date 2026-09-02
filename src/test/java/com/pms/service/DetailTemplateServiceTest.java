package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.domain.ProcessingPreset;
import com.pms.dto.request.DetailTemplateRequest;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.DetailImageGroupRepository;
import com.pms.repository.ProcessingPresetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pms.repository.DetailTemplateRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DetailTemplate business logic: the single-default-per-tenant rule (CarrierRate/ThumbnailTemplate
 * pattern) + block validation. Mirror of {@code ThumbnailTemplateServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class DetailTemplateServiceTest {

    @Mock private DetailTemplateRepository detailTemplateRepository;
    @Mock private ProcessingPresetRepository processingPresetRepository;
    @Mock private DetailImageGroupRepository detailImageGroupRepository;

    @InjectMocks private DetailTemplateServiceImpl service;

    private DetailBlock text(String bind) {
        return DetailBlock.builder().type("text").bind(bind).build();
    }

    @Test
    void create_savesBlocks() {
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").blocks(List.of(text("brandName"))).build();
        DetailTemplateResponse response = service.create(request);

        assertThat(response.getName()).isEqualTo("T");
        assertThat(response.getBlocks()).extracting(DetailBlock::getBind).containsExactly("brandName");
    }

    @Test
    void create_isDefaultTrue_demotesExistingDefault() {
        DetailTemplate existingDefault = DetailTemplate.builder()
                .id(1L).name("A").active(true).isDefault(true).build();
        given(detailTemplateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(existingDefault));
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("B").blocks(List.of(text("brandName"))).isDefault(true).build();
        service.create(request);

        ArgumentCaptor<DetailTemplate> captor = ArgumentCaptor.forClass(DetailTemplate.class);
        verify(detailTemplateRepository, times(2)).save(captor.capture());
        // First save demotes the existing default (A), second saves the new default (B).
        assertThat(captor.getAllValues().get(0).getName()).isEqualTo("A");
        assertThat(captor.getAllValues().get(0).getIsDefault()).isFalse();
        assertThat(captor.getAllValues().get(1).getName()).isEqualTo("B");
        assertThat(captor.getAllValues().get(1).getIsDefault()).isTrue();
    }

    @Test
    void update_nullBlocks_keepsExisting() {
        DetailTemplate existing = DetailTemplate.builder()
                .id(1L).name("A").blocks(List.of(text("brandName")))
                .active(true).isDefault(false).build();
        given(detailTemplateRepository.findScopedById(1L)).willReturn(Optional.of(existing));
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // request.blocks == null → keep-existing (partial update)
        DetailTemplateRequest request = DetailTemplateRequest.builder().name("B").build();
        service.update(1L, request);

        ArgumentCaptor<DetailTemplate> captor = ArgumentCaptor.forClass(DetailTemplate.class);
        verify(detailTemplateRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("B");
        assertThat(captor.getValue().getBlocks()).extracting(DetailBlock::getBind)
                .containsExactly("brandName"); // not overwritten
    }

    @Test
    void delete_removesTemplate() {
        DetailTemplate existing = DetailTemplate.builder().id(1L).name("A").active(true).isDefault(false).build();
        given(detailTemplateRepository.findScopedById(1L)).willReturn(Optional.of(existing));

        service.delete(1L);

        verify(detailTemplateRepository).delete(existing);
    }

    // ---- validateBlocks: representative violations only (framework repetition avoided) ----

    @Test
    void create_textBlockMissingBind_throws() {
        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").blocks(List.of(DetailBlock.builder().type("text").build())).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("텍스트 블록은 bind");
        verify(detailTemplateRepository, never()).save(any());
    }

    @Test
    void create_spacerMissingHeight_throws() {
        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").blocks(List.of(DetailBlock.builder().type("spacer").build())).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("여백 블록은 높이");
        verify(detailTemplateRepository, never()).save(any());
    }

    @Test
    void create_unknownBlockType_throws() {
        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").blocks(List.of(DetailBlock.builder().type("carousel").build())).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 블록 타입");
        verify(detailTemplateRepository, never()).save(any());
    }

    // ---- imageZone bind must exist in the tenant catalog (FEATURE_2609_03) ----

    @Test
    void create_imageZoneBindNotInCatalog_throws() {
        given(detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc()).willReturn(List.of());

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T")
                .blocks(List.of(DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("등록되지 않은 이미지 그룹입니다");
        verify(detailTemplateRepository, never()).save(any());
    }

    @Test
    void create_imageZoneBindInCatalog_saves() {
        given(detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc()).willReturn(List.of(
                com.pms.domain.DetailImageGroup.builder()
                        .id(1L).code("product_photos").name("제품 사진").sortOrder(0).build()));
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T")
                .blocks(List.of(DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();
        DetailTemplateResponse response = service.create(request);

        assertThat(response.getBlocks()).hasSize(1);
    }

    // ---- image processing preset reference (FEATURE_2608_08) ----

    @Test
    void create_presetIdSet_resolvesAndAttaches() {
        ProcessingPreset preset = ProcessingPreset.builder().id(7L).name("W").active(true).build();
        given(processingPresetRepository.findScopedById(7L)).willReturn(Optional.of(preset));
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").imageProcessingPresetId(7L).build();
        DetailTemplateResponse response = service.create(request);

        assertThat(response.getImageProcessingPresetId()).isEqualTo(7L);
    }

    @Test
    void create_presetIdNotFound_throws404() {
        given(processingPresetRepository.findScopedById(9L)).willReturn(Optional.empty());

        DetailTemplateRequest request = DetailTemplateRequest.builder()
                .name("T").imageProcessingPresetId(9L).build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(detailTemplateRepository, never()).save(any());
    }

    @Test
    void update_nullPresetId_keepsExistingPreset() {
        ProcessingPreset preset = ProcessingPreset.builder().id(7L).name("W").active(true).build();
        DetailTemplate existing = DetailTemplate.builder()
                .id(1L).name("A").active(true).isDefault(false).imageProcessingPreset(preset).build();
        given(detailTemplateRepository.findScopedById(1L)).willReturn(Optional.of(existing));
        given(detailTemplateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // request.imageProcessingPresetId == null → keep-existing
        DetailTemplateRequest request = DetailTemplateRequest.builder().name("B").build();
        DetailTemplateResponse response = service.update(1L, request);

        assertThat(response.getImageProcessingPresetId()).isEqualTo(7L); // not cleared
    }
}
