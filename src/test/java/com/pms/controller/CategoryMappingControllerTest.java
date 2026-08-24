package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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

    private Long categoryId;

    @BeforeEach
    void seedCategory() {
        categoryId = categoryRepository.save(Category.builder().name("신발").build()).getId();
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
}
