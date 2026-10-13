package com.pms.service;

import com.pms.domain.MasterPlatformCategory;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.MasterPlatformCategoryRequest;
import com.pms.exception.BusinessException;
import com.pms.repository.MasterPlatformCategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
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
import static org.mockito.Mockito.verify;

/**
 * Master own platform category (FEATURE_2610_12 / D40): one row per (master, platform) — an upsert replaces the
 * existing row, a code missing from the platform list is a 400 before anything is saved, and deleting a row that
 * does not exist is a 404.
 */
@ExtendWith(MockitoExtension.class)
class MasterPlatformCategoryServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterPlatformCategoryRepository masterPlatformCategoryRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @InjectMocks private MasterPlatformCategoryServiceImpl service;

    private static final Long MASTER_ID = 1L;

    private MasterPlatformCategoryRequest request(String platform, String code) {
        return MasterPlatformCategoryRequest.builder()
                .platform(platform).platformCategoryId(code).platformCategoryName("가구>소파").build();
    }

    private PlatformCategory node(String code) {
        return PlatformCategory.builder().id(40L).platform(Platform.COUPANG).code(code).name("소파").build();
    }

    @Test
    void upsert_existingRow_replacesItsCategory() {
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "80000"))
                .willReturn(Optional.of(node("80000")));
        given(masterPlatformCategoryRepository.findByMasterProductIdAndPlatform(MASTER_ID, Platform.COUPANG))
                .willReturn(Optional.of(MasterPlatformCategory.builder().id(7L).platform(Platform.COUPANG)
                        .platformCategory(node("70000")).build()));
        given(masterPlatformCategoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var response = service.upsert(MASTER_ID, request("COUPANG", "80000"));

        ArgumentCaptor<MasterPlatformCategory> captor = ArgumentCaptor.forClass(MasterPlatformCategory.class);
        verify(masterPlatformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);          // same row, not a second one
        assertThat(captor.getValue().getPlatformCategory().getCode()).isEqualTo("80000");
        assertThat(response.getPlatformCategoryId()).isEqualTo("80000");
        assertThat(response.getPlatformCategoryName()).isEqualTo("가구>소파");
    }

    @Test
    void upsert_elevenstCodeNotInList_throws400_andNothingSaved() {
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.ELEVENST, "999"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(MASTER_ID, request("ELEVENST", "999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 카테고리 목록에 없는 코드입니다.");
        verify(masterPlatformCategoryRepository, never()).save(any());
    }

    @Test
    void delete_noRow_throws404() {
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).build()));
        given(masterPlatformCategoryRepository.findByMasterProductIdAndPlatform(MASTER_ID, Platform.COUPANG))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(MASTER_ID, Platform.COUPANG))
                .isInstanceOf(BusinessException.class);
    }
}
