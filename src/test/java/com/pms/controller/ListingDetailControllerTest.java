package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Detail-HTML endpoints (Step 2-2): preview authority + 404, and the raw-HTML override lifecycle
 * (PUT → MANUAL_OVERRIDE, DELETE → AUTO). The generator is pure (no I/O), so no mocks are needed;
 * detail-content correctness is covered by {@code TemplateDetailContentGeneratorTest}.
 */
class ListingDetailControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;

    private static final String PATH = "/api/admin/product-listings";
    private Long listingId;

    @BeforeEach
    void seedCell() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("X").name("셀").seller(seller).build());
        listingId = listing.getId();
    }

    // ---- detail-preview authority + 404 ----

    @Test
    void detailPreview_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-preview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void detailPreview_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-preview")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailPreview_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-preview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.html").exists());
    }

    @Test
    void detailPreview_missingCell_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/detail-preview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- override lifecycle: PUT (MANUAL_OVERRIDE) → DELETE (AUTO) ----

    @Test
    void putDetailHtml_noToken_returns401() throws Exception {
        mockMvc.perform(put(PATH + "/" + listingId + "/detail-html")
                        .contentType("application/json").content("{\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putDetailHtml_userToken_returns403() throws Exception {
        mockMvc.perform(put(PATH + "/" + listingId + "/detail-html")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"html\":\"<p>x</p>\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void putDetailHtml_adminToken_setsManualOverride_thenGeneratedReflectsIt() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("html", "<p>edited</p>"));
        mockMvc.perform(put(PATH + "/" + listingId + "/detail-html")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MANUAL_OVERRIDE"))
                .andExpect(jsonPath("$.data.detailHtml").value("<p>edited</p>"));

        mockMvc.perform(get(PATH + "/" + listingId + "/generated")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MANUAL_OVERRIDE"))
                .andExpect(jsonPath("$.data.detailHtml").value("<p>edited</p>"));
    }

    @Test
    void deleteDetailHtml_adminToken_revertsToAuto() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("html", "<p>edited</p>"));
        mockMvc.perform(put(PATH + "/" + listingId + "/detail-html")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        mockMvc.perform(delete(PATH + "/" + listingId + "/detail-html")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("AUTO"));
    }
}
