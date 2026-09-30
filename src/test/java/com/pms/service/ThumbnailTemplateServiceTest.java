package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.ImageOp;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ThumbnailTemplate business logic: the single-default-per-tenant rule (CarrierRate pattern). Renderer
 * is a no-op collaborator here (create/update do not render).
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailTemplateServiceTest {

    @Mock private ThumbnailTemplateRepository templateRepository;
    @Mock private ThumbnailRenderer renderer;
    @Mock private ThumbnailPresetResolver thumbnailPresetResolver;
    @Mock private ImageProcessor imageProcessor;
    @Mock private ImageDecodeSupport imageDecodeSupport;

    @InjectMocks private ThumbnailTemplateServiceImpl service;

    @Test
    void create_isDefaultTrue_demotesExistingDefault() {
        ThumbnailTemplate existingDefault = ThumbnailTemplate.builder()
                .id(1L).name("A").canvasWidth(1000).canvasHeight(1000).active(true).isDefault(true).build();
        given(templateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(existingDefault));
        given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("B").canvasWidth(1000).canvasHeight(1000).isDefault(true).build();
        service.create(request);

        ArgumentCaptor<ThumbnailTemplate> captor = ArgumentCaptor.forClass(ThumbnailTemplate.class);
        verify(templateRepository, times(2)).save(captor.capture());
        // First save demotes the existing default (A), second saves the new default (B).
        assertThat(captor.getAllValues().get(0).getName()).isEqualTo("A");
        assertThat(captor.getAllValues().get(0).getIsDefault()).isFalse();
        assertThat(captor.getAllValues().get(1).getName()).isEqualTo("B");
        assertThat(captor.getAllValues().get(1).getIsDefault()).isTrue();
    }

    @Test
    void create_gradientManual_missingColor_throws() {
        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("G").canvasWidth(1000).canvasHeight(1000)
                .backgroundMode(BackgroundMode.GRADIENT_MANUAL)
                .gradientTopColor("#FF0000") // gradientBottomColor missing
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수동 그라데이션");
        verify(templateRepository, never()).save(any());
    }

    @Test
    void create_nullMode_defaultsToWhite() {
        given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("W").canvasWidth(1000).canvasHeight(1000).build(); // backgroundMode null
        service.create(request);

        ArgumentCaptor<ThumbnailTemplate> captor = ArgumentCaptor.forClass(ThumbnailTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().getBackgroundMode()).isEqualTo(BackgroundMode.WHITE);
    }

    // ---- Template input fields (Step 13) ----

    private TemplateField field(String key, String defaultValue) {
        return TemplateField.builder().key(key).label(key).defaultValue(defaultValue).build();
    }

    @Test
    void create_customFieldMissingDefault_throws() {
        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("F").canvasWidth(1000).canvasHeight(1000)
                .fields(List.of(field("promo", ""))) // custom key, blank default → invalid
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("커스텀 필드는 기본값");
        verify(templateRepository, never()).save(any());
    }

    @Test
    void create_duplicateFieldKey_throws() {
        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("F").canvasWidth(1000).canvasHeight(1000)
                .fields(List.of(field("promo", "a"), field("promo", "b")))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(templateRepository, never()).save(any());
    }

    @Test
    void create_fieldsRoundTripInResponse() {
        given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder()
                .name("F").canvasWidth(1000).canvasHeight(1000)
                .fields(List.of(field("brandName", ""), field("promo", "세일")))
                .build();
        ThumbnailTemplateResponse response = service.create(request);

        assertThat(response.getFields()).extracting(TemplateField::getKey)
                .containsExactly("brandName", "promo");
    }

    @Test
    void update_nullFields_keepsExisting() {
        ThumbnailTemplate existing = ThumbnailTemplate.builder()
                .id(1L).name("A").canvasWidth(1000).canvasHeight(1000)
                .backgroundMode(BackgroundMode.WHITE)
                .fields(List.of(field("brandName", ""))) // existing fields must survive
                .active(true).isDefault(false).build();
        given(templateRepository.findById(1L)).willReturn(Optional.of(existing));
        given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // request.fields == null → keep-existing (partial update)
        ThumbnailTemplateRequest request = ThumbnailTemplateRequest.builder().name("B").build();
        service.update(1L, request);

        ArgumentCaptor<ThumbnailTemplate> captor = ArgumentCaptor.forClass(ThumbnailTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().getFields()).extracting(TemplateField::getKey)
                .containsExactly("brandName"); // not overwritten with an empty list
    }

    @Test
    void preview_productImagePreset_processesPlaceholderBeforeRender() {
        // 2609_81/D15: the preview burns the same product-photo preset onto the gray placeholder.
        List<ImageOp> ops = List.of(ImageOp.builder().type("overlay").assetStorageKey("wm.png").build());
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .id(1L).name("A").canvasWidth(400).canvasHeight(400)
                .elements(List.of(TemplateElement.builder().type("image").bind("productImage")
                        .region(TemplateElement.Region.builder().x(0).y(0).w(400).h(400).build())
                        .processingPresetId(5L).build()))
                .active(true).isDefault(false).build();
        given(templateRepository.findById(1L)).willReturn(Optional.of(template));
        given(thumbnailPresetResolver.productImageOps(template)).willReturn(ops);
        given(imageDecodeSupport.flattenOnWhite(any())).willReturn(new byte[]{8});
        given(imageProcessor.process(org.mockito.AdditionalMatchers.aryEq(new byte[]{8}), eq(ops))).willReturn(new byte[]{7});
        given(renderer.render(any(), any(), any())).willReturn(new byte[]{1});

        service.preview(ThumbnailPreviewRequest.builder().templateId(1L).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> images = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(any(), any(), images.capture());
        assertThat(images.getValue().get("productImage")).isEqualTo(new byte[]{7});
    }
}
