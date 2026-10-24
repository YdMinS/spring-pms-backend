package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/settlement/month-summary} (FEATURE_2610_29 D30) — ADMIN role, optional {@code sellerId}, three amounts.
 * The date basis is checked in {@code SettlementPayoutMonthSumTest} and the KST month in
 * {@code SettlementMonthSummaryServiceImplTest}.
 */
class SettlementMonthSummaryControllerTest extends BaseIntegrationTest {

    private static final String MONTH_SUMMARY = "/api/admin/settlement/month-summary";

    @Test
    void monthSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get(MONTH_SUMMARY)).andExpect(status().isUnauthorized());
    }

    @Test
    void monthSummary_userToken_returns403() throws Exception {
        mockMvc.perform(get(MONTH_SUMMARY).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void monthSummary_adminToken_returnsThreeAmounts() throws Exception {
        mockMvc.perform(get(MONTH_SUMMARY).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.paid").isNumber())
                .andExpect(jsonPath("$.data.remaining").isNumber());
    }
}
