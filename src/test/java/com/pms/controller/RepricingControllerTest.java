package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마진 경보 조회 API — ADMIN 권한(401/403/200) + 빈 테넌트 배선.
 *
 * <p>마진 판정 자체는 {@code RepricingServiceTest} 가 실제 공식으로 검증한다. 여기서는 권한과 배선만 본다
 * (같은 {@code /api/admin/**} 핸들러라 401/403 은 엔드포인트 하나로 충분).</p>
 */
class RepricingControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/repricing/candidates";

    @Test
    void testCandidatesRequiresAuth() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void testCandidatesForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void testCandidatesReturnsEmptyForTenantWithoutCells() throws Exception {
        mockMvc.perform(get(PATH).param("scope", "ALL").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.groups").isEmpty())
                .andExpect(jsonPath("$.data.rows").isEmpty());
    }
}
