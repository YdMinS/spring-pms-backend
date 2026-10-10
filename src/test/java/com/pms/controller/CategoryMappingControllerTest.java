package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category mapping endpoints (FEATURE_2608_06 / 44): authority (401/403) + upsert/list roundtrip (200),
 * delete (204) / missing (404), and category-not-found (404) on the ADMIN-only
 * {@code /api/admin/category-mappings} routes (global {@code /api/admin/**} rule).
 */
class CategoryMappingControllerTest extends BaseIntegrationTest {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;

    private Category category;
    private Long categoryId;

    @BeforeEach
    void seedCategory() {
        category = categoryRepository.save(Category.builder().name("신발").build());
        categoryId = category.getId();
        // 2610_05/D38: body()'s code "101" must exist in the platform category list for the upsert to save.
        platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("101").name("경로").build());
    }

    private String path() {
        return "/api/admin/category-mappings/categories/" + categoryId + "/mappings";
    }

    private String body() {
        return "{\"platform\":\"COUPANG\",\"platformCategoryId\":\"101\",\"platformCategoryName\":\"경로\"}";
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void getMappings_noToken_returns401() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    void getMappings_userToken_returns403() throws Exception {
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void putMapping_userToken_returns403() throws Exception {
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
    }

    // ---- happy path: upsert then list ----

    @Test
    void putThenGetMapping_adminToken_returns200() throws Exception {
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.platformCategoryId").value("101"));

        mockMvc.perform(get(path()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"));
    }

    @Test
    void putMapping_categoryNotFound_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/category-mappings/categories/999999/mappings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
    }

    // ---- delete: present → 204, missing → 404 ----

    @Test
    void deleteMapping_present_returns204_missing_returns404() throws Exception {
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());

        mockMvc.perform(delete(path() + "/COUPANG").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(path() + "/COUPANG").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- 2610_05/D33: last mapping of a category a master uses → 400, mapping kept ----

    @Test
    void deleteMapping_lastMappingOfCategoryUsedByMaster_returns400() throws Exception {
        masterProductRepository.save(MasterProduct.builder().name("신발 마스터").active(true).category(category).build());
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());

        mockMvc.perform(delete(path() + "/COUPANG").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 카테고리를 쓰는 마스터가 있어 마지막 연결은 지울 수 없습니다."));

        mockMvc.perform(get(path()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---- 2610_05/D38: a code missing from the platform category list → 400, nothing saved ----

    @Test
    void putMapping_codeNotInPlatformCategoryList_returns400() throws Exception {
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"COUPANG\",\"platformCategoryId\":\"999\",\"platformCategoryName\":\"경로\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("쿠팡 카테고리 목록에 없는 코드입니다."));

        mockMvc.perform(get(path()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- FEATURE_2610_10 / D21 ③ ④: an 11st leaf from the imported list saves; a missing code names 11st ----

    @Test
    void putMapping_elevenstLeafInList_returns200AndLinksPlatformCategory() throws Exception {
        PlatformCategory elevenstLeaf = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.ELEVENST).code("1017898").name("스마트워치").build());

        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ELEVENST\",\"platformCategoryId\":\"1017898\","
                                + "\"platformCategoryName\":\"스마트기기 > 스마트워치\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("ELEVENST"))
                .andExpect(jsonPath("$.data.platformCategoryId").value("1017898"));

        assertThat(categoryMappingRepository.findByCategoryIdAndPlatform(categoryId, Platform.ELEVENST)
                .orElseThrow().getPlatformCategory().getId()).isEqualTo(elevenstLeaf.getId());
    }

    @Test
    void putMapping_elevenstCodeNotInList_returns400WithElevenstText() throws Exception {
        mockMvc.perform(put(path()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ELEVENST\",\"platformCategoryId\":\"999\","
                                + "\"platformCategoryName\":\"경로\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("11번가 카테고리 목록에 없는 코드입니다."));
    }
}
