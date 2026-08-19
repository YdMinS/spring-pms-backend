package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.service.ImageStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Master image-pool endpoints (37): authority (401/403/success) for POST/GET/PUT-zones/PUT-source/DELETE
 * + master 404. {@link ImageStorageService} is stubbed (no disk/S3); {@code ImageValidator} runs for real
 * against a PNG.
 *
 * <p>Neither {@code MasterProductImage} nor {@code MasterImageZoneAssignment} has a {@code @TenantId}
 * (isolation via the master), so happy paths work under the single {@code @Transactional} session (no
 * live-tenant guard to mismatch), same as {@code MasterProductControllerTest}. The {@code @AfterEach}
 * clears mappings before images before masters (FK order); rollback also cleans up.</p>
 */
class MasterProductImageControllerTest extends BaseIntegrationTest {

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductImageRepository imageRepository;
    @Autowired private MasterImageZoneAssignmentRepository assignmentRepository;
    @MockBean private ImageStorageService imageStorageService;

    private static final String ZONE = "product_photos";
    private Long masterId;

    @BeforeEach
    void seedMaster() {
        masterId = masterProductRepository.save(
                MasterProduct.builder().name("마스터A").active(true).build()).getId();
    }

    @AfterEach
    void cleanup() {
        assignmentRepository.deleteAll();
        imageRepository.deleteAll();
    }

    private String path() {
        return "/api/admin/master-products/" + masterId + "/images";
    }

    private MockMultipartFile pngUpload() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }

    private Long seedPoolImage(int sortOrder) {
        return imageRepository.save(MasterProductImage.builder()
                .masterProduct(masterProductRepository.findScopedById(masterId).orElseThrow())
                .sortOrder(sortOrder).imageUrl("u" + sortOrder).build()).getId();
    }

    // ------------------------------------------------------------------ POST /images (pool upload)

    @Test
    void upload_noToken_returns401() throws Exception {
        mockMvc.perform(multipart(path()).file(pngUpload()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_userToken_returns403() throws Exception {
        mockMvc.perform(multipart(path()).file(pngUpload())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_adminToken_returns201() throws Exception {
        given(imageStorageService.uploadBytes(any(), eq("master-pool"), anyString(), anyString()))
                .willReturn("master-pool/photo.png");

        mockMvc.perform(multipart(path()).file(pngUpload())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.imageUrl").value("master-pool/photo.png"))
                .andExpect(jsonPath("$.data.isSource").value(false))
                .andExpect(jsonPath("$.data.assignedZones.length()").value(0));
    }

    @Test
    void upload_missingMaster_returns404() throws Exception {
        mockMvc.perform(multipart("/api/admin/master-products/999999/images")
                        .file(pngUpload())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ GET /images

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    void list_userToken_returns403() throws Exception {
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_adminToken_returns200() throws Exception {
        seedPoolImage(0);
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isSource").value(false));
    }

    // ------------------------------------------------------------------ PUT /zones/{zoneId}/images

    @Test
    void setZoneImages_noToken_returns401() throws Exception {
        mockMvc.perform(put(path().replace("/images", "/zones/" + ZONE + "/images"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setZoneImages_userToken_returns403() throws Exception {
        mockMvc.perform(put(path().replace("/images", "/zones/" + ZONE + "/images"))
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setZoneImages_adminToken_returns200() throws Exception {
        Long a = seedPoolImage(0);
        Long b = seedPoolImage(1);
        String body = "{\"imageIds\":[" + b + "," + a + "]}";
        mockMvc.perform(put(path().replace("/images", "/zones/" + ZONE + "/images"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(b))
                .andExpect(jsonPath("$.data[0].assignedZones[0]").value(ZONE));
    }

    // ------------------------------------------------------------------ PUT /source-image

    @Test
    void setSourceImage_noToken_returns401() throws Exception {
        mockMvc.perform(put("/api/admin/master-products/" + masterId + "/source-image")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setSourceImage_userToken_returns403() throws Exception {
        mockMvc.perform(put("/api/admin/master-products/" + masterId + "/source-image")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setSourceImage_adminToken_returns200() throws Exception {
        Long id = seedPoolImage(0);
        mockMvc.perform(put("/api/admin/master-products/" + masterId + "/source-image")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.isSource").value(true));
    }

    @Test
    void setSourceImage_null_returns204() throws Exception {
        mockMvc.perform(put("/api/admin/master-products/" + masterId + "/source-image")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":null}"))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ DELETE /images/{imageId}

    @Test
    void delete_noToken_returns401() throws Exception {
        mockMvc.perform(delete(path() + "/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void delete_userToken_returns403() throws Exception {
        mockMvc.perform(delete(path() + "/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_adminToken_returns204() throws Exception {
        Long id = seedPoolImage(0);
        mockMvc.perform(delete(path() + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
