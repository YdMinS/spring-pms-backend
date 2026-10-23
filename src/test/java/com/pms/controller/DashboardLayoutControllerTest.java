package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.repository.MemberDashboardRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard API (FEATURE_2610_29 D6 · D9 · D11) — 401 without a token, a USER has a dashboard too, web and mobile
 * keep each other's place, a USER cannot add an admin card.
 */
class DashboardLayoutControllerTest extends BaseIntegrationTest {

    private static final String LAYOUT = "/api/dashboard/layout";

    @Autowired private MemberDashboardRepository memberDashboardRepository;

    @AfterEach
    void cleanup() {
        memberDashboardRepository.deleteAll();
    }

    @Test
    void layout_noToken_returns401() throws Exception {
        mockMvc.perform(get(LAYOUT)).andExpect(status().isUnauthorized());
    }

    @Test
    void layout_userToken_returnsNotSavedAndNotAdmin() throws Exception {
        mockMvc.perform(get(LAYOUT).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admin").value(false))
                .andExpect(jsonPath("$.data.saved").value(false))
                .andExpect(jsonPath("$.data.cards").isEmpty());
    }

    /** D11: the web save is read back, then a mobile save keeps the web place of the same card. */
    @Test
    void saveWebThenMobile_keepsEachOthersPlace() throws Exception {
        mockMvc.perform(put(LAYOUT + "/web").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"cardType\":\"DAILY_SALES_CHART\",\"period\":\"DAYS_30\","
                                + "\"x\":4,\"y\":0,\"w\":4,\"h\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admin").value(true))
                .andExpect(jsonPath("$.data.saved").value(true));

        mockMvc.perform(put(LAYOUT + "/mobile").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"cardType\":\"DAILY_SALES_CHART\",\"period\":\"DAYS_7\","
                                + "\"order\":0,\"w\":2}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(LAYOUT).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(true))
                .andExpect(jsonPath("$.data.cards[0].cardType").value("DAILY_SALES_CHART"))
                .andExpect(jsonPath("$.data.cards[0].period").value("DAYS_7"))
                .andExpect(jsonPath("$.data.cards[0].webX").value(4))
                .andExpect(jsonPath("$.data.cards[0].webW").value(4))
                .andExpect(jsonPath("$.data.cards[0].webH").value(2))
                .andExpect(jsonPath("$.data.cards[0].mobileOrder").value(0))
                .andExpect(jsonPath("$.data.cards[0].mobileW").value(2));
    }

    /** D9: a USER cannot store an admin card. */
    @Test
    void saveWeb_userTokenWithAdminOnlyCard_returns400() throws Exception {
        mockMvc.perform(put(LAYOUT + "/web").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"cardType\":\"PERIOD_SALES\",\"period\":null,"
                                + "\"x\":0,\"y\":0,\"w\":2,\"h\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("관리자 전용 카드입니다: PERIOD_SALES"));
    }
}
