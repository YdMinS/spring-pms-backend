package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailRenderer;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-layer propagation endpoints (FEATURE_2608_06 / 3d): authority (401/403/2xx) on all three endpoints,
 * plus pending-sync returning a seeded pending cell. Live channel/render/storage collaborators are mocked
 * ({@code @MockBean}) so nothing external is hit (03~05 convention).
 */
class ListingPropagationControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;

    @MockBean private CoupangApiClient coupangApiClient;
    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private Long masterId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).build());
        masterId = master.getId();

        // One on-market cell already flagged pending (needs_market_sync = true) → pending-sync should list it.
        productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("SP-1").name("셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).needsMarketSync(true).build());
    }

    /** Delete the listing graph before base cleanup (keeps other tenant-owned FKs clean). */
    @AfterEach
    void cleanupListingGraph() {
        productListingRepository.deleteAll();
    }

    // ---- propagate (POST /api/admin/master-products/{id}/propagate) ----

    @Test
    void propagate_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/master-products/" + masterId + "/propagate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void propagate_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/master-products/" + masterId + "/propagate")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void propagate_adminToken_returns200() throws Exception {
        // The seeded cell has no generated assets → skipped (no regenerate) → 200 summary.
        mockMvc.perform(post("/api/admin/master-products/" + masterId + "/propagate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    // ---- pending-sync (GET /api/admin/listings/pending-sync) ----

    @Test
    void pendingSync_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/listings/pending-sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void pendingSync_userToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/listings/pending-sync")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingSync_adminToken_returnsSeededPendingCell() throws Exception {
        mockMvc.perform(get("/api/admin/listings/pending-sync")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].masterProductName").value("운동화 마스터"))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"));
    }

    // ---- push-sync (POST /api/admin/listings/push-sync) ----

    @Test
    void pushSync_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/listings/push-sync")
                        .contentType("application/json").content("{\"listingIds\":[999999]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pushSync_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/listings/push-sync")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"listingIds\":[999999]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pushSync_adminToken_returns200() throws Exception {
        // Bogus id → skipped → 200 summary (no adapter involved).
        mockMvc.perform(post("/api/admin/listings/push-sync")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"listingIds\":[999999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skipped").value(1));
    }
}
