package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.DetailBlock;
import com.pms.domain.DetailImageGroup;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailImageGroupRepository;
import com.pms.repository.DetailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Detail image group catalog endpoints: authority (401/403/200) per verb + the delete rule. Code
 * derivation and the active-template-only counting rule are covered by the service unit tests.
 */
class DetailImageGroupControllerTest extends BaseIntegrationTest {

    @Autowired private DetailImageGroupRepository detailImageGroupRepository;
    @Autowired private DetailTemplateRepository detailTemplateRepository;

    private static final String PATH = "/api/admin/detail-image-groups";
    private Long groupId;

    @BeforeEach
    void seedGroup() {
        groupId = detailImageGroupRepository.save(DetailImageGroup.builder()
                .code("product_photos").name("제품 사진").sortOrder(0).build()).getId();
    }

    private String createJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", "연출컷"));
    }

    private void seedTemplateUsing(String zoneCode, boolean active) {
        detailTemplateRepository.save(DetailTemplate.builder()
                .name("기본 상세 템플릿")
                .blocks(List.of(DetailBlock.builder().type("imageZone").bind(zoneCode).build()))
                .active(active).isDefault(false).build());
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void list_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * SecurityConfig declares {@code /api/admin/**} per HTTP method, so a GET-only check would still pass
     * if the DELETE matcher were missing.
     */
    @Test
    void delete_userToken_returns403() throws Exception {
        mockMvc.perform(delete(PATH + "/" + groupId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_returns200WithDerivedCode() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("연출컷"))
                .andExpect(jsonPath("$.data.code").exists())
                .andExpect(jsonPath("$.data.sortOrder").value(1)); // seeded group holds 0
    }

    @Test
    void delete_usedByActiveTemplate_returns400() throws Exception {
        seedTemplateUsing("product_photos", true);

        mockMvc.perform(delete(PATH + "/" + groupId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("기본 상세 템플릿")));
    }

    @Test
    void delete_unusedGroup_returns200AndDisappearsFromList() throws Exception {
        mockMvc.perform(delete(PATH + "/" + groupId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'product_photos')]").isEmpty());
    }
}
