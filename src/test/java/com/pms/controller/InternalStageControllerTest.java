package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.reservation.InternalShipmentStageService;
import com.pms.service.reservation.InternalStageResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** InternalStageController 보안(401/403/200) 테스트. 서비스는 @MockBean. */
public class InternalStageControllerTest extends BaseIntegrationTest {

    @MockBean
    private InternalShipmentStageService internalShipmentStageService;

    @Test
    public void testInternalAcknowledgeWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/orders/internal-acknowledge")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testInternalAcknowledgeWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/orders/internal-acknowledge")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testInternalAcknowledgeWithAdminTokenReturnsResult() throws Exception {
        given(internalShipmentStageService.markInternal(any()))
                .willReturn(new InternalStageResult(1, 1, List.of(), List.of()));

        mockMvc.perform(post("/api/admin/orders/internal-acknowledge")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedShipments").value(1));
    }

    @Test
    public void testReleaseInternalWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/orders/internal-acknowledge/release")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testReleaseInternalWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/orders/internal-acknowledge/release")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testReleaseInternalWithAdminTokenReturnsResult() throws Exception {
        given(internalShipmentStageService.releaseInternal(any()))
                .willReturn(new InternalStageResult(1, 1, List.of(), List.of()));

        mockMvc.perform(post("/api/admin/orders/internal-acknowledge/release")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedShipments").value(1));
    }
}
