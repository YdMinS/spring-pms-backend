package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category tree browse endpoint (FEATURE_2608_06 / 52): authority (401/403) on the ADMIN-only
 * {@code GET /api/admin/category/tree} (global {@code /api/admin/**} rule) + the response shape (root children
 * with a leaf flag).
 */
class CategoryControllerTest extends BaseIntegrationTest {

    @Autowired private CategoryRepository categoryRepository;

    private Long branchId;

    @BeforeEach
    void seedTree() {
        Category branch = categoryRepository.save(Category.builder().name("패션").build());
        categoryRepository.save(Category.builder().name("운동화").parent(branch).build());  // makes 패션 non-leaf
        categoryRepository.save(Category.builder().name("가방").build());                     // leaf
        branchId = branch.getId();
    }

    @Test
    void tree_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/category/tree")).andExpect(status().isUnauthorized());
    }

    @Test
    void tree_userToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/category/tree").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void tree_rootLevel_adminToken_returns200WithLeafFlags() throws Exception {
        // Root level (parentId omitted): name-sorted 가방(leaf) then 패션(non-leaf).
        mockMvc.perform(get("/api/admin/category/tree").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("가방"))
                .andExpect(jsonPath("$.data[0].leaf").value(true))
                .andExpect(jsonPath("$.data[1].name").value("패션"))
                .andExpect(jsonPath("$.data[1].leaf").value(false));
    }

    @Test
    void tree_childLevel_adminToken_returns200() throws Exception {
        mockMvc.perform(get("/api/admin/category/tree?parentId=" + branchId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("운동화"))
                .andExpect(jsonPath("$.data[0].leaf").value(true));
    }
}
