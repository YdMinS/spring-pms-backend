package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 *   <li>2610_05/D32: {@code POST /api/admin/category} needs {@code mapping} (platform + platform category) and
 *       saves the category and that mapping together, the mapping linked to the platform_category row of its code
 *       (2610_05/D38); without it → 400. The legacy platform/code fields stay optional (2610_05/D31).</li>
 * </ul>
 */
class CategoryControllerTest extends BaseIntegrationTest {

    private static final String MAPPING =
            "\"mapping\":{\"platform\":\"COUPANG\",\"platformCategoryId\":\"101\",\"platformCategoryName\":\"패션의류>운동화\"}";

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;

    private Long branchId;

    @BeforeEach
    void seedTree() {
        Category branch = categoryRepository.save(Category.builder().name("패션").build());
        categoryRepository.save(Category.builder().name("운동화").parent(branch).build());  // makes 패션 non-leaf
        categoryRepository.save(Category.builder().name("가방").build());                     // leaf
        branchId = branch.getId();
        // 2610_05/D38: MAPPING's code "101" must exist in the platform category list for a create to save.
        platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("101").name("운동화").build());
    }

    // ── 2610_05/D32: create = category + platform mapping in one request ─────

    @Test
    void create_nameAndMapping_adminToken_returns201AndSavesMapping() throws Exception {
        // No legacy platform / platformCategoryId → still null; the platform category lives in the mapping.
        String body = mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규분류\"," + MAPPING + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("신규분류"))
                .andExpect(jsonPath("$.data.platform").value(nullValue()))
                .andExpect(jsonPath("$.data.platformCategoryId").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Long createdId = objectMapper.readTree(body).path("data").path("id").asLong();

        List<CategoryMapping> mappings = categoryMappingRepository.findByCategoryId(createdId);
        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).getPlatform()).isEqualTo(Platform.COUPANG);
        assertThat(mappings.get(0).getPlatformCategoryId()).isEqualTo("101");
        assertThat(mappings.get(0).getPlatformCategory().getCode()).isEqualTo("101");   // 2610_05/D38: FK filled
    }

    @Test
    void create_withoutMapping_adminToken_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규분류\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("플랫폼 카테고리를 함께 선택해야 합니다."));
    }

    @Test
    void create_withPlatformAndCode_adminToken_returns201() throws Exception {
        // Backward compatibility (2610_05/D31): the legacy platform + code still round-trip next to the mapping.
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"기존분류\",\"platform\":\"COUPANG\",\"platformCategoryId\":\"12345\"," + MAPPING + "}"))
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
                        .content("{\"name\":\"자식\",\"parentId\":" + branchId + "," + MAPPING + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("자식"))
                .andExpect(jsonPath("$.data.parentId").value(branchId));
    }

    @Test
    void create_unknownParentId_adminToken_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"고아\",\"parentId\":999999," + MAPPING + "}"))
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
