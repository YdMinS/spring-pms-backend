package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
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
import static org.mockito.Mockito.verify;

/**
 * Category mapping CRUD (FEATURE_2608_06 / 44): upsert (new / update same row), list, category-absent 404 on
 * upsert, and missing-mapping 404 on delete.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMappingServiceTest {

    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private CategoryMappingServiceImpl service;

    private CategoryMappingRequest request(String code) {
        return CategoryMappingRequest.builder()
                .platform("COUPANG").platformCategoryId(code).platformCategoryName("경로").build();
    }

    @Test
    void upsert_new_savesMapping() {
        given(categoryRepository.findById(3L)).willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, "COUPANG")).willReturn(Optional.empty());
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CategoryMappingResponse resp = service.upsertMapping(3L, request("101"));

        assertThat(resp.getPlatform()).isEqualTo("COUPANG");
        assertThat(resp.getPlatformCategoryId()).isEqualTo("101");
        ArgumentCaptor<CategoryMapping> captor = ArgumentCaptor.forClass(CategoryMapping.class);
        verify(categoryMappingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();                     // new insert, not update
        assertThat(captor.getValue().getCategory().getId()).isEqualTo(3L);
    }

    @Test
    void upsert_existing_updatesSameRow() {
        Category category = Category.builder().id(3L).name("신발").build();
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(category).platform("COUPANG").platformCategoryId("old").build();
        given(categoryRepository.findById(3L)).willReturn(Optional.of(category));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, "COUPANG")).willReturn(Optional.of(existing));
        given(categoryMappingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertMapping(3L, request("102"));

        ArgumentCaptor<CategoryMapping> captor = ArgumentCaptor.forClass(CategoryMapping.class);
        verify(categoryMappingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);                // same row updated
        assertThat(captor.getValue().getPlatformCategoryId()).isEqualTo("102");
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
                CategoryMapping.builder().category(category).platform("COUPANG").platformCategoryId("101").build()));

        List<CategoryMappingResponse> resp = service.getMappings(3L);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).getPlatform()).isEqualTo("COUPANG");
    }

    @Test
    void deleteMapping_missing_throws404() {
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, "NAVER")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMapping(3L, "NAVER"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteMapping_present_deletes() {
        CategoryMapping existing = CategoryMapping.builder()
                .id(9L).category(Category.builder().id(3L).build()).platform("COUPANG").platformCategoryId("101").build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(3L, "COUPANG")).willReturn(Optional.of(existing));

        service.deleteMapping(3L, "COUPANG");

        verify(categoryMappingRepository).delete(existing);
    }
}
