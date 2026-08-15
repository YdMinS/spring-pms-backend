package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.TemplateAsset;
import com.pms.repository.TemplateAssetRepository;
import com.pms.security.TenantContext;
import com.pms.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Template asset endpoints: authority (upload three ways) + list happy path + delete endpoint wiring.
 * {@link ImageStorageService} is stubbed so there is no disk/network I/O; {@code ImageValidator} runs for
 * real against a genuine PNG.
 *
 * <p>Note: the delete <em>happy path</em> and its cross-tenant guard are covered by
 * {@code TemplateAssetServiceTest}. They cannot be asserted here because {@link BaseIntegrationTest}'s
 * single {@code @Transactional} session resolves the tenant once to {@code NO_TENANT}, so every
 * {@code @TenantId} row is stamped {@code -1} (SELECTs stay self-consistent — hence {@code list} works —
 * but the delete guard reads the request's live token tenant and mismatches). We instead assert the
 * endpoint is wired and ADMIN-authorized via a deterministic missing-id 404.</p>
 */
public class TemplateAssetControllerTest extends BaseIntegrationTest {

    @Autowired private TemplateAssetRepository templateAssetRepository;
    @MockBean private ImageStorageService imageStorageService;

    private MockMultipartFile pngUpload() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("file", "badge.png", "image/png", out.toByteArray());
    }

    // ---- Authority (MUST-KEEP): representative endpoint (upload), three ways ----

    @Test
    public void upload_noToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/admin/thumbnail-assets").file(pngUpload()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void upload_userToken_returns403() throws Exception {
        mockMvc.perform(multipart("/api/admin/thumbnail-assets").file(pngUpload())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void upload_adminToken_returns200() throws Exception {
        given(imageStorageService.uploadBytes(any(), eq("thumbnail-assets"), anyString(), anyString()))
                .willReturn("thumbnail-assets/badge.png");

        mockMvc.perform(multipart("/api/admin/thumbnail-assets").file(pngUpload())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("badge"))
                .andExpect(jsonPath("$.data.storageKey").value("thumbnail-assets/badge.png"));
    }

    // ---- Happy path: list returns the tenant's assets ----

    @Test
    public void list_adminToken_returnsTenantAssets() throws Exception {
        templateAssetRepository.save(TemplateAsset.builder()
                .name("무료배송").storageKey("thumbnail-assets/f.png").contentType("image/png").build());

        mockMvc.perform(get("/api/admin/thumbnail-assets")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("무료배송"));
    }

    // ---- Delete endpoint wiring: ADMIN-authorized (not 401/403); missing id → 404 handler ----

    @Test
    public void delete_adminToken_missingId_returns404() throws Exception {
        TenantContext.set(1L);
        long missing = templateAssetRepository.findAllByOrderByIdDesc().stream()
                .mapToLong(TemplateAsset::getId).max().orElse(0L) + 999L;

        mockMvc.perform(delete("/api/admin/thumbnail-assets/" + missing)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- Rename endpoint wiring: ADMIN-authorized (not 401/403); missing id → 404 handler ----
    // (rename happy path + cross-tenant guard are covered by TemplateAssetServiceTest, same @TenantId
    //  transaction caveat as delete above.)

    @Test
    public void rename_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/admin/thumbnail-assets/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void rename_userToken_returns403() throws Exception {
        mockMvc.perform(patch("/api/admin/thumbnail-assets/1")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void rename_adminToken_missingId_returns404() throws Exception {
        TenantContext.set(1L);
        long missing = templateAssetRepository.findAllByOrderByIdDesc().stream()
                .mapToLong(TemplateAsset::getId).max().orElse(0L) + 999L;

        mockMvc.perform(patch("/api/admin/thumbnail-assets/" + missing)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isNotFound());
    }
}
