package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}
