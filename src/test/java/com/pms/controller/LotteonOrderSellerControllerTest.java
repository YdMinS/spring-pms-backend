package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonInvoiceRecordsResponse.Record;
import com.pms.service.lotteon.LotteonOrderSellerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2611_23 / D19 ⑥-5 · R18: the LotteON delivery-record list is open to any signed-in user. */
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
}
