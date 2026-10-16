package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.ElevenstCancelRequestResponse;
import com.pms.service.elevenst.ElevenstCancelRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_17 / D19 · D38: read = any signed-in user · approve / reject = ADMIN only · reject body checks. */
class ElevenstCancelRequestControllerTest extends BaseIntegrationTest {

    @MockBean private ElevenstCancelRequestService cancelRequestService;

    @Test
    void list_userRole_returns200() throws Exception {
        given(cancelRequestService.listOpen(10L)).willReturn(List.of(new ElevenstCancelRequestResponse(
                7L, 2, "품절/재고없음 — 다른 데서 샀어요", LocalDateTime.of(2026, 10, 15, 10, 0), false)));

        mockMvc.perform(get("/api/orders/10/elevenst-cancel-requests")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].reason").value("품절/재고없음 — 다른 데서 샀어요"));
    }

    @Test
    void approve_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst-cancel-requests/7/approve"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst-cancel-requests/7/approve")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        verify(cancelRequestService, never()).approve(any());
    }

    @Test
    void reject_withoutInvoice_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst-cancel-requests/7/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryCompanyCode\":\"00034\"}"))
                .andExpect(status().isBadRequest());

        verify(cancelRequestService, never()).reject(any(), any());
    }

    @Test
    void reject_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/elevenst-cancel-requests/7/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryCompanyCode\":\"00034\",\"invoiceNumber\":\"612345678901\","
                                + "\"sendDate\":\"2026-10-15\"}"))
                .andExpect(status().isOk());

        verify(cancelRequestService).reject(any(), any());
    }
}
