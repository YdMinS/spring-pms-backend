package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.repository.CarrierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class CarrierControllerTest extends BaseIntegrationTest {

    @Autowired
    private CarrierRepository carrierRepository;

    @Test
    public void createCarrier_asAdmin_201() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("name", "롯데택배", "isActive", true));

        mockMvc.perform(post("/api/admin/carriers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("롯데택배"));
    }

    @Test
    public void createCarrier_asUser_403() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("name", "롯데택배", "isActive", true));

        mockMvc.perform(post("/api/admin/carriers")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void getCarriers_authenticated_200() throws Exception {
        // USER token: GET is authenticated (not ADMIN-only) — dropdown source.
        mockMvc.perform(get("/api/admin/carriers")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void updateCarrier_asAdmin_replacesFields_200() throws Exception {
        Carrier saved = carrierRepository.save(
                Carrier.builder().name("롯데택배").isActive(true).build());
        String requestJson = objectMapper.writeValueAsString(
                Map.of("name", "CJ대한통운", "isActive", false));

        mockMvc.perform(patch("/api/admin/carriers/" + saved.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("CJ대한통운"))
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    public void createCarrier_missingName_400() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("isActive", true));

        mockMvc.perform(post("/api/admin/carriers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void deleteCarrier_notFound_404() throws Exception {
        mockMvc.perform(delete("/api/admin/carriers/9999")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void getCarriers_noToken_401() throws Exception {
        mockMvc.perform(get("/api/admin/carriers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
