package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

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
                        "isDefault", false,
                        "widthCm", new BigDecimal("22.0"),
                        "lengthCm", new BigDecimal("19.0"),
                        "heightCm", new BigDecimal("9.0")
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    /**
     * FEATURE_2609_56 / PLAN D10: the field is gone from PackageRequest, but an older mobile build still
     * sends it. Jackson must ignore the unknown property instead of failing the request — otherwise a
     * store-lagged app cannot create a box at all.
     */
    @Test
    public void createPackageIgnoresUnknownEffectiveDate() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "OLD-CLIENT",
                        "cost", new BigDecimal("2.50"),
                        "effectiveDate", LocalDate.now().toString(),
                        "isDefault", false,
                        "widthCm", new BigDecimal("22.0"),
                        "lengthCm", new BigDecimal("19.0"),
                        "heightCm", new BigDecimal("9.0")
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
                        "isDefault", false,
                        "widthCm", new BigDecimal("22.0"),
                        "lengthCm", new BigDecimal("19.0"),
                        "heightCm", new BigDecimal("9.0")
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
                        "isDefault", false
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    /** PLAN 2609_38 D5: 0 is the backfill "unset" marker, so it must never be accepted as input. */
    @Test
    public void testCreatePackageZeroSizeReturnsBadRequest() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of(
                        "type", "S",
                        "cost", new BigDecimal("2.50"),
                        "isDefault", false,
                        "widthCm", BigDecimal.ZERO,
                        "lengthCm", new BigDecimal("19.0"),
                        "heightCm", new BigDecimal("9.0")
                )
        );

        mockMvc.perform(post("/api/admin/package")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
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
        mockMvc.perform(get("/api/admin/package/" + seededPackageId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testGetPackageUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/package/" + seededPackageId))
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
                        "isDefault", false,
                        "widthCm", new BigDecimal("27.0"),
                        "lengthCm", new BigDecimal("18.0"),
                        "heightCm", new BigDecimal("15.0")
                )
        );

        mockMvc.perform(patch("/api/admin/package/" + seededPackageId)
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
                        "isDefault", true,
                        "widthCm", new BigDecimal("22.0"),
                        "lengthCm", new BigDecimal("19.0"),
                        "heightCm", new BigDecimal("9.0")
                )
        );

        mockMvc.perform(patch("/api/admin/package/" + seededPackageId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/package/" + seededPackageId)
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
                        "isDefault", false,
                        "widthCm", new BigDecimal("27.0"),
                        "lengthCm", new BigDecimal("18.0"),
                        "heightCm", new BigDecimal("15.0")
                )
        );

        mockMvc.perform(patch("/api/admin/package/" + seededPackageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // DELETE /api/admin/package/{id} tests
    @Test
    public void testDeletePackageSuccess() throws Exception {
        mockMvc.perform(delete("/api/admin/package/" + seededPackageId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/package/" + seededPackageId)
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
        mockMvc.perform(delete("/api/admin/package/" + seededPackageId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // POST /api/admin/package/{id}/image tests (FEATURE_2609_40)
    @Test
    public void testUploadImageRequiresAuth() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "box.jpg", "image/jpeg", jpeg);

        mockMvc.perform(multipart("/api/admin/package/" + seededPackageId + "/image").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
