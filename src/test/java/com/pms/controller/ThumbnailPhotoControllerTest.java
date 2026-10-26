package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductImage;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailCollage;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailCollageRepository;
import com.pms.service.collage.CollageLayouts;
import com.pms.service.collage.CollageScopes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thumbnail photo stage endpoints (FEATURE_2610_32): authority (401/403), stage order listing ?? seller ?? master
 * (D4 · D14), the six sources (D32), pool-only choices (D7), clearing, {@code usedForThumbnail} (D11), the cascade
 * when the pool photo is deleted (D10) and {@code collageUsed} (D31).
 */
class ThumbnailPhotoControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository componentRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private MasterProductImageRepository masterProductImageRepository;
    @Autowired private MasterImageZoneAssignmentRepository assignmentRepository;
    @Autowired private ThumbnailCollageRepository thumbnailCollageRepository;
    @PersistenceContext private EntityManager entityManager;

    private MasterProduct master;
    private Long sellerId;
    private Long listingId;
    private Long productA;
    private Long productB;
    private Long imageOne;
    private Long imageTwo;

    private String base() {
        return "/api/admin/master-products/" + master.getId() + "/thumbnail-photo";
    }

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        Product a = productRepository.save(Product.builder().productName("빼빼로").price(BigDecimal.ONE)
                .imageUrl("products/a.png").active(true).build());
        Product b = productRepository.save(Product.builder().productName("벨트젤리").price(BigDecimal.ONE)
                .active(true).build());
        productA = a.getId();
        productB = b.getId();
        master = masterProductRepository.save(MasterProduct.builder().name("과자 세트").active(true).build());
        componentRepository.save(MasterProductComponent.builder().masterProduct(master).product(a).build());
        componentRepository.save(MasterProductComponent.builder().masterProduct(master).product(b).build());
        listingId = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("X").name("셀").seller(seller).masterProduct(master)
                .build()).getId();
        imageOne = masterProductImageRepository.save(MasterProductImage.builder()
                .masterProduct(master).sortOrder(0).imageUrl("https://cdn/one.jpg").build()).getId();
        imageTwo = masterProductImageRepository.save(MasterProductImage.builder()
                .masterProduct(master).sortOrder(1).imageUrl("https://cdn/two.jpg").build()).getId();
    }

    private String body(String stage, Long seller, Long listing, Long imageId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("stage", stage);
        body.put("sellerId", seller);
        body.put("listingId", listing);
        body.put("imageId", imageId);
        return objectMapper.writeValueAsString(body);
    }

    private void choose(String stage, Long seller, Long listing, Long imageId) throws Exception {
        mockMvc.perform(put(base()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body(stage, seller, listing, imageId)))
                .andExpect(status().isOk());
    }

    @Test
    void stages_noToken_returns401() throws Exception {
        mockMvc.perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test
    void update_userToken_returns403() throws Exception {
        mockMvc.perform(put(base()).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body("MASTER", null, null, imageOne)))
                .andExpect(status().isForbidden());
    }

    /** Nothing stored: MASTER, SELLER, LISTING rows — all follow the first component. */
    @Test
    void stages_nothingStored_followFirstComponent() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].stage").value("MASTER"))
                .andExpect(jsonPath("$.data[1].stage").value("SELLER"))
                .andExpect(jsonPath("$.data[1].sellerName").value("행복상회"))
                .andExpect(jsonPath("$.data[2].listingId").value(listingId))
                .andExpect(jsonPath("$.data[2].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[2].source").value("FIRST_COMPONENT"))
                .andExpect(jsonPath("$.data[2].imageId").doesNotExist())
                .andExpect(jsonPath("$.data[2].collageUsed").value(false));
    }

    /** D4 · D14: listing ?? seller ?? master; clearing the listing row falls back to the seller row. */
    @Test
    void update_listingFollowsNearestStage() throws Exception {
        choose("MASTER", null, null, imageOne);
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[0].source").value("OWN"))
                .andExpect(jsonPath("$.data[1].source").value("MASTER"))
                .andExpect(jsonPath("$.data[2].source").value("MASTER"))
                .andExpect(jsonPath("$.data[2].imageId").value(imageOne))
                .andExpect(jsonPath("$.data[2].imageUrl").value("https://cdn/one.jpg"));

        choose("SELLER", sellerId, null, imageTwo);
        choose("LISTING", null, listingId, imageOne);
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[1].source").value("OWN"))
                .andExpect(jsonPath("$.data[2].source").value("OWN"))
                .andExpect(jsonPath("$.data[2].imageId").value(imageOne));

        mockMvc.perform(put(base()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body("LISTING", null, listingId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].source").value("SELLER"))
                .andExpect(jsonPath("$.data[2].imageId").value(imageTwo));
    }

    /** D4 · D24 · D32: without any stage row, the cover photo, then the legacy upload. */
    @Test
    void stages_coverThenLegacyUpload() throws Exception {
        MasterProductImage one = masterProductImageRepository.findById(imageOne).orElseThrow();
        MasterImageZoneAssignment cover = assignmentRepository.save(MasterImageZoneAssignment.builder()
                .image(one).zoneId(MasterImageZoneAssignment.SOURCE_ZONE).sortOrder(0).build());
        masterProductRepository.save(master.toBuilder().sourceImageUrl("https://cdn/legacy.jpg").build());
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[2].source").value("COVER"))
                .andExpect(jsonPath("$.data[2].imageId").value(imageOne));

        assignmentRepository.delete(cover);
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[2].source").value("LEGACY_UPLOAD"))
                .andExpect(jsonPath("$.data[2].imageId").doesNotExist())
                .andExpect(jsonPath("$.data[2].imageUrl").doesNotExist());
    }

    /** D7: a photo outside this master's pool is refused. */
    @Test
    void update_imageOfAnotherMaster_returns400() throws Exception {
        MasterProduct other = masterProductRepository.save(MasterProduct.builder().name("다른").active(true).build());
        Long foreign = masterProductImageRepository.save(MasterProductImage.builder()
                .masterProduct(other).sortOrder(0).imageUrl("https://cdn/x.jpg").build()).getId();
        mockMvc.perform(put(base()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body("MASTER", null, null, foreign)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미지 목록 불일치"));
    }

    /** D11 · D10: the pool flags the chosen photo; deleting it from the pool clears the stage. */
    @Test
    void poolFlagsChosenPhoto_andDeletingItClearsTheStage() throws Exception {
        choose("LISTING", null, listingId, imageTwo);
        mockMvc.perform(get("/api/admin/master-products/" + master.getId() + "/images")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[0].usedForThumbnail").value(false))
                .andExpect(jsonPath("$.data[1].usedForThumbnail").value(true));

        mockMvc.perform(delete("/api/admin/master-products/" + master.getId() + "/images/" + imageTwo)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        // The test transaction spans every request: push the pool delete to the DB so its ON DELETE CASCADE runs.
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[2].source").value("FIRST_COMPONENT"));
    }

    /** D6 · D31: a switched-on master collage (2 components) is reported on every stage. */
    @Test
    void stages_collageUsed() throws Exception {
        thumbnailCollageRepository.save(ThumbnailCollage.builder().masterProduct(master)
                .scopeKey(CollageScopes.MASTER).enabled(true)
                .layout(CollageLayouts.initial(List.of(productA, productB))).build());
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[0].collageUsed").value(true))
                .andExpect(jsonPath("$.data[2].collageUsed").value(true));
    }
}
