package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마진 경보 API — ADMIN 권한(401/403/200) + 빈 테넌트 배선 + 상한(400).
 *
 * <p>실행 동작(재계산·전송)은 {@code RepricingRecalculateTest}/{@code RepricingPushTest} 가 검증한다.
 * 여기서는 권한·배선·요청 DTO 가 소유한 상한만 본다(같은 {@code /api/admin/**} 핸들러라 401/403 은
 * 엔드포인트마다 한 번이면 충분).</p>
 */
class RepricingControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/repricing/candidates";
    private static final String RECALCULATE_PATH = "/api/admin/repricing/recalculate";
    private static final String PUSH_PATH = "/api/admin/repricing/push";

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

    @Test
    void testRecalculateRequiresAuth() throws Exception {
        mockMvc.perform(post(RECALCULATE_PATH).contentType(APPLICATION_JSON)
                        .content("{\"listingIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void testPushForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(PUSH_PATH).header("Authorization", "Bearer " + userToken)
                        .contentType(APPLICATION_JSON).content("{\"optionIds\":[1]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    /** 상한은 서비스가 아니라 요청 DTO 가 소유한다(D11·D18) — 201개는 마켓을 치기 전에 400 이다. */
    @Test
    void testPushOverLimitReturns400() throws Exception {
        String optionIds = LongStream.rangeClosed(1, 201).mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        mockMvc.perform(post(PUSH_PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content("{\"optionIds\":[" + optionIds + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("나눠 실행")));
    }
}
