package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.request.CreateCategoryRequest;
import com.pms.dto.response.CategoryTreeNode;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Standard-category tree browse (FEATURE_2608_06 / 52): root vs children level, the leaf flag (true when the
 * node has no children), and name sorting. FEATURE_2610_05 / D32: create saves the category and its platform
 * mapping together, and refuses a create without a mapping. D38: the mapping links the platform_category row of
 * its (platform, code); a code missing from that list → 400 with nothing saved.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @InjectMocks private CategoryServiceImpl service;

    @Test
    void createCategory_withMapping_savesCategoryAndLinkedMapping() {
        PlatformCategory linked = PlatformCategory.builder().id(7L).platform(Platform.COUPANG).code("101").name("운동화").build();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "101")).willReturn(Optional.of(linked));
        given(categoryRepository.save(any())).willAnswer(inv -> {
            Category c = inv.getArgument(0);
            return c.toBuilder().id(5L).build();
        });
        CategoryMappingRequest mapping = CategoryMappingRequest.builder()
                .platform("COUPANG").platformCategoryId("101").platformCategoryName("패션의류>운동화").build();

        service.createCategory(new CreateCategoryRequest("운동화", null, null, null, mapping));

        ArgumentCaptor<CategoryMapping> captor = ArgumentCaptor.forClass(CategoryMapping.class);
        verify(categoryMappingRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getPlatform()).isEqualTo(Platform.COUPANG);
        assertThat(captor.getValue().getPlatformCategoryId()).isEqualTo("101");
        assertThat(captor.getValue().getPlatformCategoryName()).isEqualTo("패션의류>운동화");
        assertThat(captor.getValue().getPlatformCategory()).isSameAs(linked);
    }

    @Test
    void createCategory_withoutMapping_throws400AndSavesNothing() {
        assertThatThrownBy(() -> service.createCategory(new CreateCategoryRequest("운동화", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("플랫폼 카테고리를 함께 선택해야 합니다.");
        verify(categoryRepository, never()).save(any());
        verify(categoryMappingRepository, never()).save(any());
    }

    @Test
    void createCategory_codeNotInPlatformCategoryList_throws400AndSavesNothing() {
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "999")).willReturn(Optional.empty());
        CategoryMappingRequest mapping = CategoryMappingRequest.builder()
                .platform("COUPANG").platformCategoryId("999").platformCategoryName("없는 코드").build();

        assertThatThrownBy(() -> service.createCategory(new CreateCategoryRequest("운동화", null, null, null, mapping)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("쿠팡 카테고리 목록에 없는 코드입니다.");
        verify(categoryRepository, never()).save(any());
        verify(categoryMappingRepository, never()).save(any());
    }

    @Test
    void browse_rootLevel_flagsLeafAndSortsByName() {
        Category leaf = Category.builder().id(1L).name("가방").build();      // no children → leaf
        Category branch = Category.builder().id(2L).name("나신발").build();  // has children → not leaf
        given(categoryRepository.findByParentIsNull()).willReturn(List.of(branch, leaf));
        given(categoryRepository.existsByParentId(1L)).willReturn(false);
        given(categoryRepository.existsByParentId(2L)).willReturn(true);

        List<CategoryTreeNode> nodes = service.browse(null);

        assertThat(nodes).containsExactly(
                new CategoryTreeNode(1L, "가방", true),
                new CategoryTreeNode(2L, "나신발", false));
    }

    @Test
    void browse_childLevel_usesParentId() {
        Category child = Category.builder().id(9L).name("운동화").build();
        given(categoryRepository.findByParentId(2L)).willReturn(List.of(child));
        given(categoryRepository.existsByParentId(9L)).willReturn(false);

        assertThat(service.browse(2L)).containsExactly(new CategoryTreeNode(9L, "운동화", true));
    }
}
