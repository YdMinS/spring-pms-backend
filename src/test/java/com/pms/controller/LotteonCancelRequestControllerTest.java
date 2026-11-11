package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.LotteonCancelRequestResponse;
import com.pms.service.lotteon.LotteonCancelRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2611_23 / D24 · R24: read = any signed-in user · approve / reject = ADMIN only · reject body checks. */
class LotteonCancelRequestControllerTest extends BaseIntegrationTest {

    private static final String REJECT_BODY = "{\"reasonCode\":\"902\"}";

    @MockBean private LotteonCancelRequestService cancelRequestService;

    @Test
    void list_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/11/lotteon-cancel-requests"))
                .andExpect(status().isUnauthorized());

        verify(cancelRequestService, never()).listOpen(any());
    }

    @Test
    void list_userRole_returns200() throws Exception {
        given(cancelRequestService.listOpen(11L)).willReturn(List.of(
                new LotteonCancelRequestResponse(5L, 1, "구매의사 없어짐 — 단순 변심", null)));

        mockMvc.perform(get("/api/orders/11/lotteon-cancel-requests").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reason").value("구매의사 없어짐 — 단순 변심"));
    }

    @Test
    void approve_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/approve"))
                .andExpect(status().isUnauthorized());

        verify(cancelRequestService, never()).approve(any());
    }

    @Test
    void approve_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/approve")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        verify(cancelRequestService, never()).approve(any());
    }

    @Test
    void approve_adminRole_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(cancelRequestService).approve(5L);
    }

    @Test
    void reject_withoutReasonCode_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(cancelRequestService, never()).reject(any(), any());
    }

    @Test
    void reject_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REJECT_BODY))
                .andExpect(status().isUnauthorized());

        verify(cancelRequestService, never()).reject(any(), any());
    }

    @Test
    void reject_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/reject")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REJECT_BODY))
                .andExpect(status().isForbidden());

        verify(cancelRequestService, never()).reject(any(), any());
    }

    @Test
    void reject_adminRole_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/lotteon-cancel-requests/5/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REJECT_BODY))
                .andExpect(status().isOk());

        verify(cancelRequestService).reject(eq(5L), any());
    }
}
