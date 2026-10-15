package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Startup removal of the notice blocks (FEATURE_2610_16 / D23): only templates holding one are saved. */
@ExtendWith(MockitoExtension.class)
class NoticeBlockCleanupRunnerTest {

    @Mock private DetailTemplateRepository detailTemplateRepository;
    @InjectMocks private NoticeBlockCleanupRunner runner;

    private DetailBlock text(String bind) {
        return DetailBlock.builder().type("text").bind(bind).defaultValue("글").build();
    }

    @Test
    void run_removesNoticeBlocksOnly_andSavesOnlyTemplatesThatHadOne() {
        DetailBlock brand = text("brandName");
        DetailBlock spacer = DetailBlock.builder().type("spacer").heightPx(24).build();
        DetailTemplate withNotices = DetailTemplate.builder().id(1L).name("A").active(true).isDefault(true)
                .blocks(List.of(brand, text("asNotice"), spacer, text("returnExchangeNotice"))).build();
        DetailTemplate clean = DetailTemplate.builder().id(2L).name("B").active(true).isDefault(false)
                .blocks(List.of(text("productName"))).build();
        given(detailTemplateRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        given(detailTemplateRepository.findAll()).willReturn(List.of(withNotices, clean));

        runner.run(null);

        ArgumentCaptor<DetailTemplate> captor = ArgumentCaptor.forClass(DetailTemplate.class);
        verify(detailTemplateRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getBlocks()).containsExactly(brand, spacer);
        assertThat(TenantContext.get()).isNull();
    }
}
