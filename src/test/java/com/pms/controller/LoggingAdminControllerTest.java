package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.external.LoggingAdminService;
import com.pms.service.external.LoggingAdminService.TargetStatus;
import com.pms.service.external.LoggingTarget;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LoggingAdminController 보안(대표 1엔드포인트) + happy + 잘못된 target.
 *
 * LoggingAdminService 를 @MockBean 처리해 실제 LoggingSystem 조작 없이 컨트롤러 계층만 검증.
 */
public class LoggingAdminControllerTest extends BaseIntegrationTest {

    @MockBean
    private LoggingAdminService loggingAdminService;

    @Test
    public void targets_권한() throws Exception {
        // 401 (no token)
        mockMvc.perform(get("/api/admin/logging/targets"))
                .andExpect(status().isUnauthorized());
        // 403 (user token)
        mockMvc.perform(get("/api/admin/logging/targets")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        // 200 (admin token)
        mockMvc.perform(get("/api/admin/logging/targets")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    public void patch_happy() throws Exception {
        given(loggingAdminService.set(eq(LoggingTarget.COUPANG), eq("DEBUG")))
                .willReturn(new TargetStatus("COUPANG", "쿠팡", "DEBUG", null));

        mockMvc.perform(patch("/api/admin/logging/COUPANG")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"level\":\"DEBUG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("DEBUG"));
    }

    @Test
    public void get_잘못된target_400() throws Exception {
        mockMvc.perform(get("/api/admin/logging/UNKNOWN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
