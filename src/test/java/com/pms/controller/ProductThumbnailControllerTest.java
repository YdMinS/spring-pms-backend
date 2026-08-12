package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.FontAsset;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.repository.FontAssetRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Product thumbnail endpoints: authority (POST /generate three ways) + generate→list happy path.
 * {@link ProductImageLoader} and {@link ImageStorageService} are stubbed so the render is exercised
 * (system font seed) without disk/network I/O. Pixel content is not asserted (renderer engine test
 * covers it).
 */
public class ProductThumbnailControllerTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private FontAssetRepository fontAssetRepository;

    @MockBean private ProductImageLoader productImageLoader;
    @MockBean private ImageStorageService imageStorageService;

    private Long productId;
    private Long sellerId;

    private Long systemFontId() {
        return fontAssetRepository.findByFamilyKeyAndTenantIdIsNull("SansSerif")
                .map(FontAsset::getId)
                .orElseThrow(() -> new IllegalStateException("system font not seeded"));
    }

    @BeforeEach
    void seedFixtures() throws Exception {
        productId = productRepository.save(Product.builder()
                .productName("운동화").name("운동화").brand("나이키")
                .imageUrl("products/p.jpg").active(true).build()).getId();
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build()).getId();

        // The tenant default template (library model) with one text element (bound to the seeded font).
        String templateJson = objectMapper.writeValueAsString(Map.of(
                "name", "쿠팡 기본",
                "canvasWidth", 300,
                "canvasHeight", 300,
                "active", true,
                "isDefault", true,
                "elements", List.of(Map.of(
                        "type", "text",
                        "bind", "productName",
                        "region", Map.of("x", 0, "y", 0, "w", 300, "h", 100),
                        "align", Map.of("h", "center", "v", "center"),
                        "fontId", systemFontId(),
                        "color", "#000000",
                        "maxFontSize", 40,
                        "minFontSize", 10,
                        "maxLines", 2))));
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(templateJson))
                .andExpect(status().isCreated());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/thumb.jpg");
    }

    // ---- Authority (MUST-KEEP): one representative endpoint, three ways ----

    @Test
    public void generate_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + productId + "/thumbnails/generate")
                        .param("sellerId", String.valueOf(sellerId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void generate_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + productId + "/thumbnails/generate")
                        .header("Authorization", "Bearer " + userToken)
                        .param("sellerId", String.valueOf(sellerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    public void generate_adminToken_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + productId + "/thumbnails/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sellerId", String.valueOf(sellerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.imageUrl").value("thumbnails/thumb.jpg"))
                .andExpect(jsonPath("$.data.source").value("GENERATED"));
    }

    // ---- Happy path: generate then it appears in the per-product list ----

    @Test
    public void generateThenList_containsOneThumbnail() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + productId + "/thumbnails/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sellerId", String.valueOf(sellerId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/products/" + productId + "/thumbnails")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").value(sellerId))
                .andExpect(jsonPath("$.data[0].sellerName").value("행복상회"));
    }
}
