package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_24 / D56 — the LotteON notice preview is admin-only pure computation. */
class LotteonNoticeControllerTest extends BaseIntegrationTest {

    private static final String BODY = "{\"group\":\"의류\",\"notices\":{\"색상\":\"검정\"}}";

    @Test
    void preview_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon/notice-preview")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void preview_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon/notice-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preview_adminToken_returnsItemGroupAndValues() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon/notice-preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemGroupCode").value("01"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='0020')].value").value("검정"))
                .andExpect(jsonPath("$.data.lines[?(@.code=='0010')].value").value("상품 상세페이지 참조"))
                .andExpect(jsonPath("$.data.usedCoupangItems[0]").value("색상"));
    }
}
