package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category lookup endpoints (FEATURE_2608_06 / 45): authority (401/403/200) + tree/predict happy paths, no
 * active account 400 (no sellerId), and blank productName 400. {@link CoupangApiClient} is mocked (no live
 * HTTP) — the real adapter/resolver/service still run.
 */
class CategoryLookupControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private static final String TREE_JSON =
            "{\"code\":200,\"data\":{\"displayCategoryCode\":\"0\",\"name\":\"루트\",\"child\":["
                    + "{\"displayCategoryCode\":\"1001\",\"name\":\"패션의류잡화\",\"last\":false,"
                    + "\"child\":[{\"displayCategoryCode\":\"2001\",\"name\":\"child\",\"last\":true}]},"
                    + "{\"displayCategoryCode\":\"1002\",\"name\":\"여성 반팔티\",\"last\":true,\"child\":[]}"
                    + "]}}";

    @BeforeEach
    void stub() {
        given(coupangApiClient.get(contains("display-categories"), eq(""), any())).willReturn(TREE_JSON);
        given(coupangApiClient.post(contains("categorization/predict"), anyString(), any()))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":{\"predictedCategoryId\":\"56174\","
                        + "\"categoryName\":\"여성 반팔티\"}}");
    }

    @AfterEach
    void cleanup() {
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    /** Seed a seller with an active COUPANG account (needed for the account-resolution happy paths). */
    private void seedActiveCoupangAccount() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("메인")
                .vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void tree_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/tree"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tree_userToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/tree")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void tree_adminToken_returns200WithNodes() throws Exception {
        seedActiveCoupangAccount();
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/tree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].platformCategoryId").value("1001"))
                .andExpect(jsonPath("$.data[0].leaf").value(false))
                .andExpect(jsonPath("$.data[1].leaf").value(true));
    }

    @Test
    void predict_adminToken_returns200WithSuggestion() throws Exception {
        seedActiveCoupangAccount();
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/predict")
                        .param("productName", "여성 반팔티")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].platformCategoryId").value("56174"));
    }

    // ---- no active account (no sellerId) → 400 ----

    @Test
    void tree_noActiveAccount_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/tree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    // ---- blank productName → 400 ----

    @Test
    void predict_blankProductName_returns400() throws Exception {
        seedActiveCoupangAccount();
        mockMvc.perform(get("/api/admin/category-lookup/COUPANG/predict")
                        .param("productName", "")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
