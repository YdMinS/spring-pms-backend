package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class CarrierRateControllerTest extends BaseIntegrationTest {

    private Long createCarrierId(String name) {
        return carrierRepository.save(
                Carrier.builder().name(name).isActive(true).build()).getId();
    }

    @Test
    public void testCreateCarrierRateWithValidRequestAndAdminToken() throws Exception {
        Long carrierId = createCarrierId("롯데택배");
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrierId", carrierId,
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
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.carrierId").value(carrierId))
                .andExpect(jsonPath("$.data.carrier").value("롯데택배"));
    }

    @Test
    public void testCreateCarrierRateWithUserToken() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrierId", 1,
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
                        "carrierId", 1,
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
    public void testCreateCarrierRateWithMissingCarrierId() throws Exception {
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
        Long carrierId = createCarrierId("FedEx");
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrierId", carrierId,
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
        mockMvc.perform(get("/api/admin/carrier-rate/" + seededCarrierRateId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.carrierId").isNumber())
                .andExpect(jsonPath("$.data.carrier").value("DHL"));
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
        Long carrierId = createCarrierId("한진택배");
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrierId", carrierId,
                        "type", "EXPRESS",
                        "cost", new BigDecimal("20.00"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/carrier-rate/" + seededCarrierRateId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.carrierId").value(carrierId));
    }

    @Test
    public void testUpdateCarrierRateWithInvalidId() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "carrierId", 1,
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
                        "carrierId", 1,
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
                        "carrierId", 1,
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
        mockMvc.perform(delete("/api/admin/carrier-rate/" + seededCarrierRateId)
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
