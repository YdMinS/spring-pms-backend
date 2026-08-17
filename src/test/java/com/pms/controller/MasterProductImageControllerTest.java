package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.service.ImageStorageService;
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
 * Master input-image endpoints: authority (401/403/success) for POST/GET/PUT/DELETE + master 404.
 * {@link ImageStorageService} is stubbed (no disk/S3); {@code ImageValidator} runs for real against a PNG.
 *
 * <p>{@code MasterProductImage} has no {@code @TenantId} (isolation via the master), so — unlike the
 * TemplateAsset tests — delete/reorder happy paths work under the single {@code @Transactional} session
 * (no live-tenant guard to mismatch). {@code findScopedById(master)} resolves within the test transaction,
 * same as {@code MasterProductControllerTest}. Rollback cleans up (master_product_image, master_product).</p>
 */
class MasterProductImageControllerTest extends BaseIntegrationTest {

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductImageRepository imageRepository;
    @MockBean private ImageStorageService imageStorageService;

    private static final String ZONE = "product_photos";
    private Long masterId;

    @BeforeEach
    void seedMaster() {
        masterId = masterProductRepository.save(
                MasterProduct.builder().name("마스터A").active(true).build()).getId();
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

    private Long seedImage(int sortOrder) {
        return imageRepository.save(MasterProductImage.builder()
                .masterProduct(masterProductRepository.findScopedById(masterId).orElseThrow())
                .zoneId(ZONE).sortOrder(sortOrder).imageUrl("u" + sortOrder).build()).getId();
    }

    // ------------------------------------------------------------------ POST /images

    @Test
    void upload_noToken_returns401() throws Exception {
        mockMvc.perform(multipart(path()).file(pngUpload()).param("zoneId", ZONE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_userToken_returns403() throws Exception {
        mockMvc.perform(multipart(path()).file(pngUpload()).param("zoneId", ZONE)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_adminToken_returns201() throws Exception {
        given(imageStorageService.uploadBytes(any(), eq("master-detail"), anyString(), anyString()))
                .willReturn("master-detail/photo.png");

        mockMvc.perform(multipart(path()).file(pngUpload()).param("zoneId", ZONE)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.zoneId").value(ZONE))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.imageUrl").value("master-detail/photo.png"));
    }

    @Test
    void upload_missingMaster_returns404() throws Exception {
        mockMvc.perform(multipart("/api/admin/master-products/999999/images")
                        .file(pngUpload()).param("zoneId", ZONE)
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
        seedImage(0);
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].zoneId").value(ZONE));
    }

    // ------------------------------------------------------------------ PUT /images/reorder

    @Test
    void reorder_noToken_returns401() throws Exception {
        mockMvc.perform(put(path() + "/reorder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":\"" + ZONE + "\",\"imageIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reorder_userToken_returns403() throws Exception {
        mockMvc.perform(put(path() + "/reorder").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":\"" + ZONE + "\",\"imageIds\":[1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reorder_adminToken_returns200() throws Exception {
        Long a = seedImage(0);
        Long b = seedImage(1);
        String body = "{\"zoneId\":\"" + ZONE + "\",\"imageIds\":[" + b + "," + a + "]}";
        mockMvc.perform(put(path() + "/reorder").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(b));
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
        Long id = seedImage(0);
        mockMvc.perform(delete(path() + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
