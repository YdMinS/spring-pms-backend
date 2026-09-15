package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.PackingSavingsSummary;
import com.pms.service.packing.PackingSavingsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포장 절약 조회 API 보안(401/403/200) (FEATURE_2609_41 / PLAN 2609_41 S13).
 *
 * <p>손익과 같은 등급의 경영 데이터라 ADMIN 전용이다. 집계 자체는
 * {@code PackingSavingsServiceTest} 가 맡고, 여기서는 권한과 응답 형태만 본다.
 */
public class PackingSavingsControllerTest extends BaseIntegrationTest {

    private static final String SUMMARY_PATH = "/api/admin/packing/savings/summary";
    private static final String OPTIONS_PATH = "/api/admin/packing/savings/options";
    private static final String BOXES_PATH = "/api/admin/packing/savings/boxes";

    @MockBean
    private PackingSavingsService packingSavingsService;

    @Test
    public void testSummaryWithAdminTokenReturnsTotals() throws Exception {
        given(packingSavingsService.summary(any(), any(), any())).willReturn(new PackingSavingsSummary(
                12, new BigDecimal("18000.00"), new BigDecimal("6000.00"), new BigDecimal("24000.00"),
                5, new BigDecimal("9000.00"), 4, new BigDecimal("12000.00"), 2, 1, 3));

        mockMvc.perform(get(SUMMARY_PATH).param("from", "2026-09-01").param("to", "2026-09-30")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.parcelCount").value(12))
                .andExpect(jsonPath("$.data.totalSaving").value(24000.00))
                // 🔴 「근거 없음」과 「배송비 모름」은 다른 숫자다(S14 · S3)
                .andExpect(jsonPath("$.data.missingBasisCount").value(2))
                .andExpect(jsonPath("$.data.missingShippingFeeCount").value(3));
    }

    @Test
    public void testSummaryRequiresAuth() throws Exception {
        mockMvc.perform(get(SUMMARY_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(OPTIONS_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BOXES_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    public void testOptionsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(OPTIONS_PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BOXES_PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
