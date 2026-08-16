package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Detail-template read endpoints: authority (401/403/200) + shape. A template is seeded within the test
 * transaction (the startup-seeded default is committed under tenant 1 but the @Transactional session can't
 * see it — documented caveat), so get-by-id resolves. Render/seed correctness is covered by the pure
 * renderer + seeder units.
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
}
