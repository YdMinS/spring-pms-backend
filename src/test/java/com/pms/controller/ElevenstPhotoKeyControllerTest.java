package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.repository.TenantElevenstSettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2611_11 / D39 — the company's 11st photo lookup key: ADMIN only, write-only (never returned), blank clears. */
class ElevenstPhotoKeyControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/elevenst-photo-key";

    @Autowired private TenantElevenstSettingRepository tenantElevenstSettingRepository;

    @AfterEach
    void cleanup() {
        tenantElevenstSettingRepository.deleteAll();
    }

    @Test
    void testGetAndPutAreAdminOnly() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testPutStoresEncryptedKey_neverReturnsIt_blankClears() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"  0123456789abcdef0123456789abcdef  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("0123456789abcdef"))));
        assertThat(tenantElevenstSettingRepository.findFirstByOrderByIdAsc().orElseThrow().getPhotoApiKey())
                .isEqualTo("0123456789abcdef0123456789abcdef");
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.configured").value(false));
    }
}
