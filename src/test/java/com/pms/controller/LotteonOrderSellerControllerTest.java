package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonInvoiceRecordsResponse.Record;
import com.pms.dto.response.LotteonOrderChoicesResponse;
import com.pms.service.lotteon.LotteonOrderSellerService;
import com.pms.service.lotteon.LotteonSellerChoices;
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

/**
 * FEATURE_2611_23 / D19 ⑥-5 · R18: the LotteON delivery-record list and code lists are open to any signed-in user;
 * 06 / R24: the shipping appointment notice is ADMIN only.
 */
class LotteonOrderSellerControllerTest extends BaseIntegrationTest {

    @MockBean private LotteonOrderSellerService sellerService;

    @Test
    void invoiceRecords_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/10/lotteon-invoice-records"))
                .andExpect(status().isUnauthorized());

        verify(sellerService, never()).invoiceRecords(any());
    }

    @Test
    void invoiceRecords_userRole_returns200() throws Exception {
        given(sellerService.invoiceRecords(10L)).willReturn(List.of(new LotteonInvoiceRecordsResponse(10L, "물티슈",
                List.of(new Record("1", "A111", "0001", "롯데택배", "13", "발송완료", null)))));

        mockMvc.perform(get("/api/orders/10/lotteon-invoice-records").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].records[0].statusName").value("발송완료"));
    }

    @Test
    void invoiceRecords_notLotteon_returns400() throws Exception {
        given(sellerService.invoiceRecords(10L)).willThrow(new IllegalArgumentException("롯데ON 주문이 아닙니다"));

        mockMvc.perform(get("/api/orders/10/lotteon-invoice-records").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    private static final String APPOINTMENT_BODY = "{\"appointmentDate\":\"2026-11-20\",\"reasonCode\":\"STK_SHTG\"}";

    @Test
    void choices_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/lotteon-choices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void choices_userRole_returns200() throws Exception {
        given(sellerService.choices()).willReturn(new LotteonOrderChoicesResponse(
                LotteonSellerChoices.SELLER_CANCEL_REASONS, LotteonSellerChoices.CANCEL_REJECT_REASONS,
                LotteonSellerChoices.APPOINTMENT_REASONS, LotteonSellerChoices.APPOINTMENT_DETAIL_REASONS,
                LotteonSellerChoices.HOLD_REASONS));

        mockMvc.perform(get("/api/orders/lotteon-choices").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelReasons[0].code").value("111"));
    }

    @Test
    void deliveryAppointment_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/orders/11/lotteon-delivery-appointment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPOINTMENT_BODY))
                .andExpect(status().isUnauthorized());

        verify(sellerService, never()).deliveryAppointment(any(), any());
    }

    @Test
    void deliveryAppointment_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/orders/11/lotteon-delivery-appointment")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPOINTMENT_BODY))
                .andExpect(status().isForbidden());

        verify(sellerService, never()).deliveryAppointment(any(), any());
    }

    @Test
    void deliveryAppointment_adminRole_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/orders/11/lotteon-delivery-appointment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPOINTMENT_BODY))
                .andExpect(status().isOk());

        verify(sellerService).deliveryAppointment(eq(11L), any());
    }
}
