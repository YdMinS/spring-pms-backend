package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Category mapping CRUD (FEATURE_2608_06 / 44): upsert (new / update same row), list, category-absent 404 on
 * upsert, and missing-mapping 404 on delete. FEATURE_2610_05 / D33: the last mapping of a category a master uses
 * cannot be deleted (400). D38: upsert links the platform_category row of (platform, code) on both a new and an
 * updated mapping, and a code missing from that list → 400 with nothing saved.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMappingServiceTest {

    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CommissionPrefillService commissionPrefillService;
    @Mock private MasterProductRepository masterProductRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @InjectMocks private CategoryMappingServiceImpl service;

    private CategoryMappingRequest request(String code) {
        return CategoryMappingRequest.builder()
                .platform("COUPANG").platformCategoryId(code).platformCategoryName("경로").build();
    }

    private PlatformCategory platformCategory(Long id, String code) {
        return PlatformCategory.builder().id(id).platform(Platform.COUPANG).code(code).name("운동화").build();
    }

    @Test
    void upsert_new_savesMappingLinkedToPlatformCategory() {
        PlatformCategory linked = platformCategory(7L, "101");
        given(categoryRepository.findById(3L)).willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "101")).willReturn(Optional.of(linked));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.empty());
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CategoryMappingResponse resp = service.upsertMapping(3L, request("101"));

        assertThat(resp.getPlatform()).isEqualTo("COUPANG");
        assertThat(resp.getPlatformCategoryId()).isEqualTo("101");
        ArgumentCaptor<CategoryMapping> captor = ArgumentCaptor.forClass(CategoryMapping.class);
        verify(categoryMappingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();                     // new insert, not update
        assertThat(captor.getValue().getCategory().getId()).isEqualTo(3L);
        assertThat(captor.getValue().getPlatformCategory()).isSameAs(linked);   // 2610_05/D38: FK filled
    }

    @Test
    void upsert_existingWithoutFk_updatesSameRowAndLinksPlatformCategory() {
        Category category = Category.builder().id(3L).name("신발").build();
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(category).platform(Platform.COUPANG).platformCategoryId("old").build();
        PlatformCategory linked = platformCategory(8L, "102");
        given(categoryRepository.findById(3L)).willReturn(Optional.of(category));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "102")).willReturn(Optional.of(linked));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.of(existing));
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertMapping(3L, request("102"));

        ArgumentCaptor<CategoryMapping> captor = ArgumentCaptor.forClass(CategoryMapping.class);
        verify(categoryMappingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);                // same row updated
        assertThat(captor.getValue().getPlatformCategoryId()).isEqualTo("102");
        assertThat(captor.getValue().getPlatformCategory()).isSameAs(linked);   // 2610_05/D38: old FK-less row gets the FK
    }

    @Test
    void upsert_codeNotInPlatformCategoryList_throws400AndSavesNothing() {
        given(categoryRepository.findById(3L)).willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertMapping(3L, request("999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("쿠팡 카테고리 목록에 없는 코드입니다.");
        verify(categoryMappingRepository, never()).save(any());
        verify(commissionPrefillService, never()).prefillIfAbsent(any(), any(), any());
    }

    @Test
    void upsert_invokesCommissionPrefill() {
        given(categoryRepository.findById(3L)).willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "101"))
                .willReturn(Optional.of(platformCategory(7L, "101")));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.empty());
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertMapping(3L, request("101"));

        verify(commissionPrefillService).prefillIfAbsent(eq(3L), eq(Platform.COUPANG), eq("경로"));
    }

    @Test
    void upsert_prefillFailure_stillSavesMapping() {
        given(categoryRepository.findById(3L)).willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "101"))
                .willReturn(Optional.of(platformCategory(7L, "101")));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.empty());
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willThrow(new RuntimeException("prefill boom"))
                .given(commissionPrefillService).prefillIfAbsent(any(), any(), any());

        CategoryMappingResponse resp = service.upsertMapping(3L, request("101"));   // must not throw

        assertThat(resp.getPlatformCategoryId()).isEqualTo("101");
        verify(categoryMappingRepository).save(any());
    }

    @Test
    void upsert_categoryNotFound_throws404() {
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertMapping(99L, request("101")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMappings_returnsList() {
        Category category = Category.builder().id(3L).name("신발").build();
        given(categoryMappingRepository.findByCategoryId(3L)).willReturn(List.of(
                CategoryMapping.builder().category(category).platform(Platform.COUPANG).platformCategoryId("101").build()));

        List<CategoryMappingResponse> resp = service.getMappings(3L);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).getPlatform()).isEqualTo("COUPANG");
    }

    @Test
    void deleteMapping_missing_throws404() {
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.NAVER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMapping(3L, Platform.NAVER))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteMapping_notLastMapping_deletesWithoutMasterCheck() {
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(Category.builder().id(3L).build()).platform(Platform.COUPANG).platformCategoryId("101").build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.of(existing));
        given(categoryMappingRepository.countByCategoryId(3L)).willReturn(2L);

        service.deleteMapping(3L, Platform.COUPANG);

        verify(categoryMappingRepository).delete(existing);
        verify(masterProductRepository, never()).countAllTenantsByCategoryId(any());
    }

    @Test
    void deleteMapping_lastMappingOfCategoryUsedByMaster_throws400() {
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(Category.builder().id(3L).build()).platform(Platform.COUPANG).platformCategoryId("101").build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.of(existing));
        given(categoryMappingRepository.countByCategoryId(3L)).willReturn(1L);
        given(masterProductRepository.countAllTenantsByCategoryId(3L)).willReturn(1L);

        assertThatThrownBy(() -> service.deleteMapping(3L, Platform.COUPANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 카테고리를 쓰는 마스터가 있어 마지막 연결은 지울 수 없습니다.");
        verify(categoryMappingRepository, never()).delete(any());
    }

    @Test
    void deleteMapping_lastMappingOfUnusedCategory_deletes() {
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(Category.builder().id(3L).build()).platform(Platform.COUPANG).platformCategoryId("101").build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, Platform.COUPANG)).willReturn(Optional.of(existing));
        given(categoryMappingRepository.countByCategoryId(3L)).willReturn(1L);
        given(masterProductRepository.countAllTenantsByCategoryId(3L)).willReturn(0L);

        service.deleteMapping(3L, Platform.COUPANG);

        verify(categoryMappingRepository).delete(existing);
    }
}
