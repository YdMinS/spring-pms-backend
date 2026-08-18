package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Detail-template CRUD endpoints: authority (401/403/200) per verb + create shape. A template is seeded
 * within the test transaction (the startup-seeded default is committed under tenant 1 but the
 * @Transactional session can't see it — documented caveat), so get/patch/delete by-id resolve.
 * Render/seed/business correctness is covered by the pure renderer + service units.
 */
class DetailTemplateControllerTest extends BaseIntegrationTest {

    @Autowired private DetailTemplateRepository detailTemplateRepository;

    private static final String PATH = "/api/admin/detail-templates";
    private Long templateId;

    @BeforeEach
    void seedTemplate() {
        templateId = detailTemplateRepository.save(DetailTemplate.builder()
                .name("상세 템플릿")
                .blocks(List.of(DetailBlock.builder().type("text").bind("brandName").align("center").build()))
                .active(true).isDefault(true).build()).getId();
    }

    private String createJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", "신규 상세",
                "blocks", List.of(
                        Map.of("type", "text", "bind", "brandName"),
                        Map.of("type", "spacer", "heightPx", 24)),
                "isDefault", true));
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

    @Test
    void get_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + templateId)).andExpect(status().isUnauthorized());
    }

    @Test
    void get_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + templateId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH + "/" + templateId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(templateId))
                .andExpect(jsonPath("$.data.blocks").isArray());
    }

    // ---- POST (create) ----

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH).contentType("application/json").content(createJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_returns200WithBlocksAndDefault() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("신규 상세"))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.blocks.length()").value(2))
                .andExpect(jsonPath("$.data.blocks[1].type").value("spacer"))
                .andExpect(jsonPath("$.data.blocks[1].heightPx").value(24));
    }

    // ---- PATCH (update) ----

    @Test
    void update_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + templateId)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + templateId).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_adminToken_returns200() throws Exception {
        mockMvc.perform(patch(PATH + "/" + templateId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정"));
    }

    // ---- DELETE ----

    @Test
    void delete_noToken_returns401() throws Exception {
        mockMvc.perform(delete(PATH + "/" + templateId)).andExpect(status().isUnauthorized());
    }

    @Test
    void delete_userToken_returns403() throws Exception {
        mockMvc.perform(delete(PATH + "/" + templateId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_adminToken_returns200() throws Exception {
        mockMvc.perform(delete(PATH + "/" + templateId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
