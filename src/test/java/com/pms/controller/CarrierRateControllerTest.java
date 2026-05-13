package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class CarrierRateControllerTest extends BaseIntegrationTest {

    @Test
    public void testCreateCarrierRateWithValidRequestAndAdminToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testCreateCarrierRateWithUserToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testCreateCarrierRateWithoutToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/carrier-rate")
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testCreateCarrierRateWithMissingCarrier() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreateCarrierRateSetIsDefaultTrue() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "FedEx",
                        "type", "STANDARD",
                        "cost", new BigDecimal("10.00"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", true
                )
        );

        mockMvc.perform(post("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testGetCarrierRatesWithAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testGetCarrierRatesWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testGetCarrierRatesWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testGetCarrierRateWithValidIdAndAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testGetCarrierRateWithInvalidId() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate/9999")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testGetCarrierRateWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/carrier-rate/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateCarrierRateWithValidRequestAndAdminToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL Updated",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("20.00"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/carrier-rate/1")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testUpdateCarrierRateWithInvalidId() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/carrier-rate/9999")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateCarrierRateWithUserToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/carrier-rate/1")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateCarrierRateWithoutToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrier", "DHL",
                        "type", "EXPRESS",
                        "cost", new BigDecimal("15.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/carrier-rate/1")
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testDeleteCarrierRateWithValidIdAndAdminToken() throws Exception {
        mockMvc.perform(delete("/api/admin/carrier-rate/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testDeleteCarrierRateWithInvalidId() throws Exception {
        mockMvc.perform(delete("/api/admin/carrier-rate/9999")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testDeleteCarrierRateWithUserToken() throws Exception {
        mockMvc.perform(delete("/api/admin/carrier-rate/1")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testDeleteCarrierRateWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/admin/carrier-rate/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
