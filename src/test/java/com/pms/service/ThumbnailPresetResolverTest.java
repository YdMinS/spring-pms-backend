package com.pms.service;

import com.pms.domain.ImageOp;
import com.pms.domain.ProcessingPreset;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.ProcessingPresetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FEATURE_2609_81 D9·D14·D18: the product-photo preset is read from the FIRST productImage element only;
 * a missing preset never fails the render.
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailPresetResolverTest {

    @Mock private ProcessingPresetRepository processingPresetRepository;
    @InjectMocks private ThumbnailPresetResolver resolver;

    private static final List<ImageOp> OPS =
            List.of(ImageOp.builder().type("overlay").assetStorageKey("wm.png").build());

    @Test
    void noPresetOnBase_returnsEmpty_noLookup() {
        List<ImageOp> ops = resolver.productImageOps(template(productImage(null)));

        assertThat(ops).isEmpty();
        verify(processingPresetRepository, never()).findScopedById(any());
    }

    @Test
    void presetOnFirstProductImage_returnsItsOps() {
        given(processingPresetRepository.findScopedById(5L)).willReturn(Optional.of(
                ProcessingPreset.builder().id(5L).name("워터마크").operations(OPS).active(true).build()));

        assertThat(resolver.productImageOps(template(productImage(5L)))).isEqualTo(OPS);
    }

    @Test
    void presetNotFound_returnsEmpty() {
        given(processingPresetRepository.findScopedById(5L)).willReturn(Optional.empty());

        assertThat(resolver.productImageOps(template(productImage(5L)))).isEmpty();
    }

    @Test
    void presetOnFixedImageOrSecondProductImage_ignored() {
        TemplateElement fixed = TemplateElement.builder().type("image").src("badge.png")
                .region(region()).processingPresetId(7L).build();

        List<ImageOp> ops = resolver.productImageOps(template(productImage(null), fixed, productImage(8L)));

        assertThat(ops).isEmpty();
        verify(processingPresetRepository, never()).findScopedById(any());
    }

    private static ThumbnailTemplate template(TemplateElement... elements) {
        return ThumbnailTemplate.builder().id(1L).canvasWidth(200).canvasHeight(200)
                .elements(List.of(elements)).build();
    }

    private static TemplateElement productImage(Long presetId) {
        return TemplateElement.builder().type("image").bind("productImage")
                .region(region()).processingPresetId(presetId).build();
    }

    private static TemplateElement.Region region() {
        return TemplateElement.Region.builder().x(0).y(0).w(200).h(200).build();
    }
}
