package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class PackageControllerTest extends BaseIntegrationTest {

    // POST /api/admin/package tests
    @Test
    public void testCreatePackageSuccess() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "S",
                        "cost", new BigDecimal("2.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testCreatePackageUnauthorized() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "S",
                        "cost", new BigDecimal("2.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testCreatePackageValidationError() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "cost", new BigDecimal("2.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    // GET /api/admin/package tests
    @Test
    public void testGetPackagesSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testGetPackagesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/package"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // GET /api/admin/package/{id} tests
    @Test
    public void testGetPackageSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testGetPackageUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/package/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // PATCH /api/admin/package/{id} tests
    @Test
    public void testUpdatePackageSuccess() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "M",
                        "cost", new BigDecimal("3.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testUpdatePackageSetIsDefaultTrue() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "S",
                        "cost", new BigDecimal("2.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", true
                )
        );

        mockMvc.perform(patch("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    @Test
    public void testUpdatePackageUnauthorized() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "M",
                        "cost", new BigDecimal("3.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false
                )
        );

        mockMvc.perform(patch("/api/admin/package/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // DELETE /api/admin/package/{id} tests
    @Test
    public void testDeletePackageSuccess() throws Exception {
        mockMvc.perform(delete("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/package/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testDeletePackageNotFound() throws Exception {
        mockMvc.perform(delete("/api/admin/package/9999")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testDeletePackageUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/admin/package/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
