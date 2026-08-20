package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.ProductImageResponse;
import com.pms.service.ProductImageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Product image-gallery endpoints (39): wiring (200 happy path per verb) + authority (401 no token / 403
 * USER token) across GET/POST/PUT/DELETE. {@link ProductImageService} is mocked — this asserts controller
 * routing + the global {@code /api/admin/**} security, not business logic (covered in the service test).
 */
class ProductImageControllerTest extends BaseIntegrationTest {

    @MockBean private ProductImageService productImageService;

    private static final long PID = 10L;
    private static final String BASE = "/api/admin/products/" + PID + "/images";

    private ProductImageResponse resp() {
        return ProductImageResponse.builder().id(1L).productId(PID).sortOrder(0).imageUrl("u1").build();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    private MockMultipartFile files() {
        return new MockMultipartFile("files", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    // ------------------------------------------------------------------ happy path (200 per verb)

    @Test
    void addImages_admin_returns200() throws Exception {
        given(productImageService.addImages(anyLong(), any())).willReturn(List.of(resp()));
        mockMvc.perform(multipart(BASE).file(files())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].productId").value((int) PID));
    }

    @Test
    void list_admin_returns200() throws Exception {
        given(productImageService.list(PID)).willReturn(List.of(resp()));
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void replaceImage_admin_returns200() throws Exception {
        given(productImageService.replaceImage(anyLong(), anyLong(), any())).willReturn(resp());
        mockMvc.perform(multipart(HttpMethod.PUT, BASE + "/1").file(file())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void reorder_admin_returns200() throws Exception {
        given(productImageService.reorder(anyLong(), any())).willReturn(List.of(resp()));
        mockMvc.perform(put(BASE + "/reorder")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void deleteImage_admin_returns200() throws Exception {
        mockMvc.perform(delete(BASE + "/1").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ------------------------------------------------------------------ authority (401 / 403 per verb)

    @Test
    void post_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(multipart(BASE).file(files())).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(BASE).file(files()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(put(BASE + "/reorder")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/reorder").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(delete(BASE + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
