package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ImageOp;
import com.pms.domain.ProcessingPreset;
import com.pms.repository.ProcessingPresetRepository;
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
 * Processing-preset CRUD endpoints: authority (401/403/200) per verb + create shape. A preset is seeded
 * within the test transaction so get/patch/delete by-id resolve. Render/business correctness is covered by
 * the pure engine + service units.
 */
class ProcessingPresetControllerTest extends BaseIntegrationTest {

    @Autowired private ProcessingPresetRepository processingPresetRepository;

    private static final String PATH = "/api/admin/processing-presets";
    private Long presetId;

    @BeforeEach
    void seedPreset() {
        presetId = processingPresetRepository.save(ProcessingPreset.builder()
                .name("워터마크")
                .operations(List.of(ImageOp.builder().type("overlay").assetStorageKey("wm.png").build()))
                .active(true).build()).getId();
    }

    private String createJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", "신규 프리셋",
                "operations", List.of(
                        Map.of("type", "overlay", "assetStorageKey", "badge.png", "anchor", "TOP_LEFT"))));
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
    void get_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH + "/" + presetId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(presetId))
                .andExpect(jsonPath("$.data.operations").isArray());
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
    void create_adminToken_returns200WithOps() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("신규 프리셋"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.operations.length()").value(1))
                .andExpect(jsonPath("$.data.operations[0].type").value("overlay"))
                .andExpect(jsonPath("$.data.operations[0].anchor").value("TOP_LEFT"));
    }

    // ---- PATCH (update) ----

    @Test
    void update_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + presetId)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + presetId).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_adminToken_returns200() throws Exception {
        mockMvc.perform(patch(PATH + "/" + presetId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"name\":\"수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정"));
    }

    // ---- DELETE ----

    @Test
    void delete_noToken_returns401() throws Exception {
        mockMvc.perform(delete(PATH + "/" + presetId)).andExpect(status().isUnauthorized());
    }

    @Test
    void delete_userToken_returns403() throws Exception {
        mockMvc.perform(delete(PATH + "/" + presetId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_adminToken_returns200() throws Exception {
        mockMvc.perform(delete(PATH + "/" + presetId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
