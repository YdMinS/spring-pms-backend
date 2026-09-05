package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ClaimAction;
import com.pms.dto.response.ClaimActionResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.service.claim.ClaimActionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ClaimActionController 통합 테스트 — 권한(401/403)·요청 검증·핸들러 매핑.
 *
 * 서비스는 @MockBean 이라 가드가 돌지 않는다 → 404 는 목이 예외를 던지게 해서 <b>매핑</b>만 고정한다
 * (가드 자체는 ClaimActionServiceImplTest 담당). 액션별 필수 필드 400 은 서비스가 아니라 요청 DTO 가
 * 판정하므로(PLAN §5) 여기서 검증되는 것이 맞다.
 */
class ClaimActionControllerTest extends BaseIntegrationTest {

    @MockBean private ClaimActionService claimActionService;

    @Test
    void executeAction_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/admin/claims/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_APPROVE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executeAction_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/claims/1/actions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_APPROVE\"}"))
                .andExpect(status().isForbidden());

        verify(claimActionService, never()).execute(any(), any());
    }

    @Test
    void executeAction_invoiceActionWithoutInvoiceNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/claims/1/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_COLLECT_INVOICE\",\"deliveryCompanyCode\":\"CJGLS\"}"))
                .andExpect(status().isBadRequest());

        // 검증은 요청 DTO + 액션 메타(requires) 가 한다 — 서비스까지 가지 않는다.
        verify(claimActionService, never()).execute(any(), any());
    }

    @Test
    void executeAction_unknownClaim_returns404() throws Exception {
        given(claimActionService.execute(eq(999L), any()))
                .willThrow(new ResourceNotFoundException("Claim", 999L));

        mockMvc.perform(post("/api/admin/claims/999/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_APPROVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void executeAction_valid_returns200WithResult() throws Exception {
        given(claimActionService.execute(eq(1L), any())).willReturn(
                new ClaimActionResponse(1L, ClaimAction.RETURN_COLLECT_INVOICE, true, "200", "OK"));

        mockMvc.perform(post("/api/admin/claims/1/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_COLLECT_INVOICE\","
                                + "\"deliveryCompanyCode\":\"CJGLS\",\"invoiceNumber\":\"123456789012\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimId").value(1))
                .andExpect(jsonPath("$.data.action").value("RETURN_COLLECT_INVOICE"))
                .andExpect(jsonPath("$.data.succeeded").value(true))
                .andExpect(jsonPath("$.data.resultCode").value("200"))
                .andExpect(jsonPath("$.data.resultMessage").value("OK"));
    }

    @Test
    void executeAction_coupangRejected_returns502WithRawCodeAndMessage() throws Exception {
        // 실패도 data 를 채운다 — 원문(D15)이 없으면 실계정 디버깅에서 검색이 안 된다.
        given(claimActionService.execute(eq(1L), any())).willThrow(
                new com.pms.service.claim.ClaimActionFailedException(new ClaimActionResponse(
                        1L, ClaimAction.RETURN_APPROVE, false, "400", "이미 처리된 반품입니다")));

        mockMvc.perform(post("/api/admin/claims/1/actions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RETURN_APPROVE\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.data.resultCode").value("400"))
                .andExpect(jsonPath("$.data.resultMessage").value("이미 처리된 반품입니다"));
    }
}
