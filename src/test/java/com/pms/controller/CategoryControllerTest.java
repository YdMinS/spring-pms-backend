package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category endpoints (FEATURE_2608_06):
 * <ul>
 *   <li>52: authority (401/403) on the ADMIN-only {@code GET /api/admin/category/tree}
 *       (global {@code /api/admin/**} rule) + the response shape (root children with a leaf flag).</li>
 *   <li>55: {@code POST /api/admin/category} accepts a standard category with only {@code {name, parentId}}
 *       (platform/code now optional), while the legacy platform-carrying create still works.</li>
 * </ul>
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

    // ── 55: create with optional platform/code ──────────────────────────────

    @Test
    void create_nameOnly_adminToken_returns201WithNullPlatform() throws Exception {
        // Standard category: no platform / platformCategoryId in the body → still 201.
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규분류\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("신규분류"))
                .andExpect(jsonPath("$.data.platform").value(nullValue()))
                .andExpect(jsonPath("$.data.platformCategoryId").value(nullValue()));
    }

    @Test
    void create_withPlatformAndCode_adminToken_returns201() throws Exception {
        // Backward compatibility: legacy callers still send platform + code → 201.
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"기존분류\",\"platform\":\"COUPANG\",\"platformCategoryId\":\"12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("기존분류"))
                .andExpect(jsonPath("$.data.platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.platformCategoryId").value("12345"));
    }

    @Test
    void create_withParentId_adminToken_returns201Connected() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"자식\",\"parentId\":" + branchId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("자식"))
                .andExpect(jsonPath("$.data.parentId").value(branchId));
    }

    @Test
    void create_unknownParentId_adminToken_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"고아\",\"parentId\":999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규분류\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규분류\"}"))
                .andExpect(status().isForbidden());
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
