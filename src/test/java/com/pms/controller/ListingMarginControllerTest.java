package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEATURE_2610_27 / D9 · D12: margin endpoints — authority (401 / 403), the margin view wiring (a listing whose
 * margin cannot be calculated answers 200 with the reason, not 400) and the preview body check. FEATURE_2610_28 /
 * D20 · D21: one site entry per option on a Coupang listing.
 */
class ListingMarginControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;

    private Long listingId;
    private Long optionId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("마진상회").businessRegistration("222-33-44444").build());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("P-1").name("셀").status(ListingStatus.SELLING)
                .seller(seller).build());
        listingId = cell.getId();
        optionId = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("기본").sellingPrice(new BigDecimal("6000"))
                .marketPrice(new BigDecimal("6500")).build()).getId();
    }

    @AfterEach
    void cleanupListingGraph() {
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
    }

    private String marginsPath() {
        return "/api/admin/product-listings/" + listingId + "/margins";
    }

    private String previewPath() {
        return "/api/admin/product-listings/" + listingId + "/options/" + optionId + "/margin-preview";
    }

    @Test
    void margins_noToken_returns401() throws Exception {
        mockMvc.perform(get(marginsPath())).andExpect(status().isUnauthorized());
    }

    @Test
    void margins_userToken_returns403() throws Exception {
        mockMvc.perform(get(marginsPath()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void margins_adminToken_uncalculableListing_returns200WithReason() throws Exception {
        mockMvc.perform(get(marginsPath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.options[0].optionId").value(optionId))
                .andExpect(jsonPath("$.data.options[0].sites.length()").value(1))
                .andExpect(jsonPath("$.data.options[0].sites[0].site").value("COUPANG"))
                .andExpect(jsonPath("$.data.options[0].sites[0].judgedPrice").value(6500))
                .andExpect(jsonPath("$.data.options[0].sites[0].marginAmount").doesNotExist())
                .andExpect(jsonPath("$.data.options[0].sites[0].reason").isNotEmpty());
    }

    @Test
    void margins_unknownListing_returns404() throws Exception {
        mockMvc.perform(get("/api/admin/product-listings/999999/margins")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void preview_userToken_returns403() throws Exception {
        mockMvc.perform(post(previewPath()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":7000,\"site\":\"COUPANG\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preview_missingPrice_returns400() throws Exception {
        mockMvc.perform(post(previewPath()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
