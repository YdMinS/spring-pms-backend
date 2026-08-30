package com.pms.service;

import com.pms.domain.Category;
import com.pms.dto.response.CategoryTreeNode;
import com.pms.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Standard-category tree browse (FEATURE_2608_06 / 52): root vs children level, the leaf flag (true when the
 * node has no children), and name sorting.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private CategoryServiceImpl service;

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
