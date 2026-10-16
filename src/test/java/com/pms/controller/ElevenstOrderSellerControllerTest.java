package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.ElevenstChoicesResponse;
import com.pms.service.elevenst.ElevenstOrderSellerService;
import com.pms.service.elevenst.ElevenstSellerChoices;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FEATURE_2610_17 / D61 · D63 · D66 · D67: read = any signed-in user · writes = ADMIN only. */
class ElevenstOrderSellerControllerTest extends BaseIntegrationTest {

    @MockBean private ElevenstOrderSellerService sellerService;

    @Test
    void choices_userRole_returns200WithTheServerLists() throws Exception {
        given(sellerService.choices()).willReturn(new ElevenstChoicesResponse(
                ElevenstSellerChoices.SELLER_CANCEL_REASONS, ElevenstSellerChoices.FAULT_REJECT_REASONS,
                ElevenstSellerChoices.DELAY_REASONS));

        mockMvc.perform(get("/api/orders/elevenst-choices").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelReasons[4].label").value("고객변심"));
    }

    @Test
    void stockConfirm_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-stock-confirm")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        verify(sellerService, never()).stockConfirm(any());
    }

    @Test
    void deliveryDelay_withoutDate_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-delivery-delay")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"01\",\"reasonText\":\"재고가 곧 들어올 예정입니다\"}"))
                .andExpect(status().isBadRequest());

        verify(sellerService, never()).deliveryDelay(any(), any());
    }

    @Test
    void visitPickup_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/orders/10/elevenst-visit-pickup")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certCode\":\"A12\"}"))
                .andExpect(status().isOk());

        verify(sellerService).visitPickup(any(), any());
    }
}
