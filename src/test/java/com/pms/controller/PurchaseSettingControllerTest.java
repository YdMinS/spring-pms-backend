package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PurchaseSettingController — read: any signed-in user, write: ADMIN; no row = off (FEATURE_2610_09 D69 · D72). */
class PurchaseSettingControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/purchase-settings";

    @Test
    void testGetIsOpenToEverySignedInUserAndDefaultsOff() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockLinked").value(false));
    }

    @Test
    void testUpdateIsAdminOnlyAndPersists() throws Exception {
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stockLinked\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stockLinked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockLinked").value(true));
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockLinked").value(true));
    }
}
