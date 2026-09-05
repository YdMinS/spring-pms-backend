package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.dto.response.OrderClaimResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.service.claim.ClaimQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderClaimController 통합 테스트 — 인증(401)·목록·400/404 핸들러 매핑.
 *
 * 서비스는 @MockBean 이라 검증 로직이 돌지 않는다 → 400·404 는 목이 예외를 던지게 해서
 * <b>핸들러 매핑</b>만 고정한다(검증 자체는 ClaimQueryServiceImplTest 담당).
 * 403 테스트는 없다 — 역할 제한 없는 조회 API 이고(OrderController 와 동일),
 * 일반 사용자 토큰으로 200 이 나오는 것이 그 사실을 고정한다.
 */
class OrderClaimControllerTest extends BaseIntegrationTest {

    @MockBean private ClaimQueryService claimQueryService;

    @Test
    void getClaims_returnsList_withUserToken() throws Exception {
        given(claimQueryService.getClaims(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(sample()));

        mockMvc.perform(get("/api/claims").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].claimType").value("RETURN"))
                .andExpect(jsonPath("$.data[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data[0].externalOrderId").value("O-1"))
                .andExpect(jsonPath("$.data[0].linked").value(true));
    }

    @Test
    void getClaims_halfOpenPeriod_returns400() throws Exception {
        given(claimQueryService.getClaims(any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다."));

        mockMvc.perform(get("/api/claims")
                        .param("from", "2026-09-01")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getClaim_unknownId_returns404() throws Exception {
        given(claimQueryService.getClaim(999L)).willThrow(new ResourceNotFoundException("Claim", 999L));

        mockMvc.perform(get("/api/claims/999").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getClaims_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/claims"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getClaim_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/claims/1"))
                .andExpect(status().isUnauthorized());
    }

    private OrderClaimResponse sample() {
        return new OrderClaimResponse(1L, "COUPANG", ClaimType.RETURN, ClaimStatus.RECEIVED, "UC",
                "R-1", "O-1", "양말", 2, "CHANGEMIND", "단순변심", "CUSTOMER", 3000,
                "INV-9", "CJGLS", null, null, "홍길동", LocalDateTime.of(2026, 9, 1, 10, 0),
                5L, "테스트셀러", 10L, true);
    }
}
