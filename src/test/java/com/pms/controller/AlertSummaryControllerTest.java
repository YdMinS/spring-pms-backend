package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AlertSummaryController 통합 테스트 — 인증(401) + 응답 필드(FEATURE_2609_49 / D9 · FEATURE_2609_51).
 *
 * <p>값 자체(몇 건인가)는 서비스 단위 테스트가 단정한다 — 여기는 경계(권한·응답 모양)만 본다.
 */
class AlertSummaryControllerTest extends BaseIntegrationTest {

    @Test
    void summary_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/alerts/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void summary_returnsCounts_forAuthenticatedUser() throws Exception {
        // 역할 제한이 없다 — 일반 사용자 토큰으로 200 이어야 한다.
        mockMvc.perform(get("/api/alerts/summary").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.openClaims").exists())
                .andExpect(jsonPath("$.data.unansweredInquiries").exists())
                .andExpect(jsonPath("$.data.paidOrders").exists())
                .andExpect(jsonPath("$.data.newOrders").exists())
                .andExpect(jsonPath("$.data.todoCount").exists());
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 🔴 <b>USER 토큰으로 200</b> — D15(담당자가 ADMIN 이 아닐 수 있다)가 {@code @PreAuthorize} 가 슬쩍
     * 붙는 순간 깨지는데, 그걸 잡아주는 것은 이 테스트뿐이다.
     */
    @Test
    void listReturnsFeed() throws Exception {
        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void listRejectsUnknownType() throws Exception {
        mockMvc.perform(get("/api/alerts").param("type", "FOO")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }
}
