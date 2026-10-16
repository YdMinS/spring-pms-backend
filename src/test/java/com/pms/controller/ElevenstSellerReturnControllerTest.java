package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.elevenst.ElevenstSellerReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_17 / D35 · D39 ⑤: reasons readable by any signed-in user · request ADMIN only · body checks. */
class ElevenstSellerReturnControllerTest extends BaseIntegrationTest {

    private static final String BODY = "{\"reasonCode\":\"101\",\"reasonText\":\"변심\",\"shippingFeeMethod\":\"03\","
            + "\"completeNow\":false}";

    @MockBean private ElevenstSellerReturnService sellerReturnService;

    @Test
    void reasons_userRole_returns13() throws Exception {
        mockMvc.perform(get("/api/orders/elevenst-return-reasons").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(13));
    }

    @Test
    void request_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-return-request")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(sellerReturnService, never()).request(any(), any());
    }

    @Test
    void request_withoutReasonText_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-return-request")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"101\",\"shippingFeeMethod\":\"03\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void request_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-return-request")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(sellerReturnService).request(any(), any());
    }
}
