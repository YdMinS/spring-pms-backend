package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_13 / D97 · D99 · D100 — the 11st notice preview is admin-only pure computation. */
class ElevenstNoticeControllerTest extends BaseIntegrationTest {

    private static final String BODY = "{\"group\":\"농수축산물\",\"notices\":{\"원산지\":\"국내산\","
            + "\"ELEVENST:471785504\":\"해당 없음\"}}";

    @Test
    void preview_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst/notice-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preview_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst/notice-preview")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void preview_adminToken_returnsTypeAndOwnKeys() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst/notice-preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typeCode").value("891030"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='393972')].value").value("국내산"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='471785504')].ownKey").value("ELEVENST:471785504"))
                .andExpect(jsonPath("$.data.usedCoupangItems[0]").value("원산지"));
    }
}
