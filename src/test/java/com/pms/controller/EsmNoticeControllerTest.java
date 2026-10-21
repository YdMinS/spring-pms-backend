package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_26 / D56 — the ESM notice preview is admin-only pure computation. */
class EsmNoticeControllerTest extends BaseIntegrationTest {

    private static final String BODY = "{\"group\":\"의류\",\"notices\":{\"색상\":\"검정\","
            + "\"ESM:1-10\":\"3일\"}}";

    @Test
    void preview_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/esm/notice-preview")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void preview_adminToken_returnsGroupValuesAndOwnKeys() throws Exception {
        mockMvc.perform(post("/api/admin/esm/notice-preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupNo").value("1"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='1-2')].value").value("검정"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='1-1')].extraMark").value(true))
                .andExpect(jsonPath("$.data.lines[?(@.code=='1-10')].ownKey").value("ESM:1-10"))
                .andExpect(jsonPath("$.data.usedCoupangItems[0]").value("색상"));
    }
}
