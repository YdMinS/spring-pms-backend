package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import com.pms.domain.CollageShape;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ProductImageLoader;
import com.pms.service.collage.CollageLayouts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Collage stage endpoints (FEATURE_2610_21): authority (401/403), stage inheritance listing ?? seller ?? master
 * (D37 · D41), switch-off (D42), deletion (D21), validation (D3 · D47) and the preview JPEG (D20 · D46).
 * {@link ProductImageLoader} returns a real PNG so the collage is actually drawn.
 */
class ThumbnailCollageControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository componentRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductImageRepository productImageRepository;

    @MockBean private ProductImageLoader productImageLoader;

    private Long masterId;
    private Long sellerId;
    private Long listingId;
    private Long productA;
    private Long productB;
    private Long photoA2;
    private Long photoB1;

    private String base() {
        return "/api/admin/master-products/" + masterId + "/collage";
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @BeforeEach
    void seed() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        Product a = productRepository.save(Product.builder().productName("빼빼로").price(java.math.BigDecimal.ONE)
                .imageUrl("products/a.png").active(true).build());
        Product b = productRepository.save(Product.builder().productName("벨트젤리").price(java.math.BigDecimal.ONE)
                .active(true).build());
        productA = a.getId();
        productB = b.getId();
        MasterProduct master = masterProductRepository.save(MasterProduct.builder().name("과자 세트").active(true).build());
        masterId = master.getId();
        componentRepository.save(MasterProductComponent.builder().masterProduct(master).product(a).build());
        componentRepository.save(MasterProductComponent.builder().masterProduct(master).product(b).build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("X").name("셀").seller(seller).masterProduct(master)
                .build());
        listingId = listing.getId();

        // FEATURE_2610_32: galleries — A has its cover (sort 0) and a second photo, B has one photo but no cover.
        productImageRepository.save(ProductImage.builder().product(a).sortOrder(0).imageUrl("products/a.png").build());
        photoA2 = productImageRepository.save(ProductImage.builder().product(a).sortOrder(1)
                .imageUrl("products/a2.png").build()).getId();
        photoB1 = productImageRepository.save(ProductImage.builder().product(b).sortOrder(0)
                .imageUrl("products/b1.png").build()).getId();

        given(productImageLoader.load(any())).willReturn(png());
        given(productImageLoader.loadUrl(any())).willReturn(png());

        // Tenant default template in this session (the startup-seeded one lives under tenant 1).
        String templateJson = objectMapper.writeValueAsString(Map.of(
                "name", "쿠팡 기본", "canvasWidth", 300, "canvasHeight", 200,
                "active", true, "isDefault", true,
                "fields", List.of(),
                "elements", List.of(Map.of("type", "image", "bind", "productImage",
                        "region", Map.of("x", 0, "y", 0, "w", 240, "h", 160)))));
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(templateJson))
                .andExpect(status().isCreated());
    }

    private String saveBody(boolean enabled, CollageLayout layout) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("enabled", enabled);
        body.put("layout", layout);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void overview_noToken_returns401() throws Exception {
        mockMvc.perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test
    void save_userToken_returns403() throws Exception {
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content(saveBody(true, CollageLayouts.initial(List.of(productA, productB)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_listsStagesWithNothingStored() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bundle").value(true))
                .andExpect(jsonPath("$.data.products.length()").value(2))
                .andExpect(jsonPath("$.data.products[1].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.products[0].images.length()").value(2))
                .andExpect(jsonPath("$.data.products[0].images[1].productImageId").value(photoA2))
                .andExpect(jsonPath("$.data.products[0].images[1].imageUrl").value("products/a2.png"))
                .andExpect(jsonPath("$.data.stages.length()").value(3))
                .andExpect(jsonPath("$.data.stages[0].stage").value("MASTER"))
                .andExpect(jsonPath("$.data.stages[1].stage").value("SELLER"))
                .andExpect(jsonPath("$.data.stages[2].listingId").value(listingId))
                .andExpect(jsonPath("$.data.stages[2].source").value("NONE"));
    }

    /** D37 · D41: the listing stage without its own row uses the master default. */
    @Test
    void saveMaster_listingStageInheritsIt() throws Exception {
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(saveBody(true, CollageLayouts.initial(List.of(productA, productB)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage.source").value("OWN"))
                .andExpect(jsonPath("$.data.ownLayout.shape").value("ROW"));

        mockMvc.perform(get(base() + "/stage").param("stage", "LISTING").param("listingId", listingId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage.source").value("MASTER"))
                .andExpect(jsonPath("$.data.stage.enabled").value(true))
                .andExpect(jsonPath("$.data.ownLayout").doesNotExist())
                .andExpect(jsonPath("$.data.inheritedLayout.cells[0].productId").value(productA))
                .andExpect(jsonPath("$.data.previewTargets[0].listingId").value(listingId))
                .andExpect(jsonPath("$.data.previewTargets[0].slotWidth").value(240));
    }

    /** D42 · D21: switching the listing stage off, then deleting it, falls back to the master default. */
    @Test
    void switchOffThenDelete_listingStage() throws Exception {
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(saveBody(true, CollageLayouts.initial(List.of(productA, productB)))))
                .andExpect(status().isOk());
        mockMvc.perform(put(base() + "/stage").param("stage", "LISTING").param("listingId", listingId.toString())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(saveBody(false, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage.source").value("OWN"))
                .andExpect(jsonPath("$.data.stage.enabled").value(false))
                .andExpect(jsonPath("$.data.ownLayout").doesNotExist())
                .andExpect(jsonPath("$.data.inheritedLayout.shape").value("ROW"));

        mockMvc.perform(delete(base() + "/stage").param("stage", "LISTING").param("listingId", listingId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage.source").value("MASTER"));
    }

    @Test
    void save_cellsNotMatchingComponents_returns400() throws Exception {
        CollageLayout wrong = new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.0, "#FFFFFF",
                Boolean.TRUE, List.of(new CollageCell(productA, 1.0, 0.5, 0.5, null),
                new CollageCell(productA, 1.0, 0.5, 0.5, null)));
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(saveBody(true, wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("칸의 물품이 구성품과 다릅니다."));
    }

    @Test
    void sellerStage_unknownSeller_returns400() throws Exception {
        mockMvc.perform(get(base() + "/stage").param("stage", "SELLER").param("sellerId", "999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 마스터의 판매상품이 없는 판매자입니다."));
    }

    /** D20 · D46: the preview draws with the listing's template and returns a JPEG; nothing is stored. */
    @Test
    void preview_withListing_returnsJpeg() throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("listingId", listingId);
        body.put("layout", CollageLayouts.initial(List.of(productA, productB)));
        byte[] jpeg = mockMvc.perform(post(base() + "/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertThat(image.getWidth()).isEqualTo(300);
        assertThat(image.getHeight()).isEqualTo(200);
    }

    /** FEATURE_2610_32 D16: the chosen cell photo is stored and returned. */
    @Test
    void save_cellPhoto_isStored() throws Exception {
        CollageLayout layout = new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.0, "#FFFFFF",
                Boolean.TRUE, List.of(new CollageCell(productA, 1.0, 0.5, 0.5, photoA2),
                new CollageCell(productB, 1.0, 0.5, 0.5, null)));
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(saveBody(true, layout)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownLayout.cells[0].productImageId").value(photoA2))
                .andExpect(jsonPath("$.data.ownLayout.cells[1].productImageId").doesNotExist());
    }

    /** FEATURE_2610_32 D17: a photo of another product is refused on save. */
    @Test
    void save_cellPhotoOfAnotherProduct_returns400() throws Exception {
        CollageLayout layout = new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.0, "#FFFFFF",
                Boolean.TRUE, List.of(new CollageCell(productA, 1.0, 0.5, 0.5, photoB1),
                new CollageCell(productB, 1.0, 0.5, 0.5, null)));
        mockMvc.perform(put(base() + "/stage").param("stage", "MASTER")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(saveBody(true, layout)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("칸의 사진이 그 칸 물품의 사진이 아닙니다."));
    }

    /** FEATURE_2610_32 D15 · D17: the chosen photo is drawn; a cover-less product with a chosen photo is drawn too. */
    @Test
    void preview_chosenPhotos_areLoaded() throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("listingId", listingId);
        body.put("layout", new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.0, "#FFFFFF",
                Boolean.TRUE, List.of(new CollageCell(productA, 1.0, 0.5, 0.5, photoA2),
                new CollageCell(productB, 1.0, 0.5, 0.5, photoB1))));
        mockMvc.perform(post(base() + "/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));

        verify(productImageLoader).loadUrl("products/a2.png");
        verify(productImageLoader).loadUrl("products/b1.png");
    }
}
