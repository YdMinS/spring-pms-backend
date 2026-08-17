package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Seller;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MarginPolicy ADMIN authority (POST three ways) + create happy path.
 * Same global {@code /api/admin/**} handler → 401/403 verified once (POST) per CLAUDE CUT guidance.
 */
class MarginPolicyControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;

    private static final String PATH = "/api/admin/margin-policies";
    private Long sellerId;

    @BeforeEach
    void seedSeller() {
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build()).getId();
    }

    private String body() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sellerId", sellerId, "platform", "COUPANG", "marginRate", new BigDecimal("0.1500")));
    }

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH).contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void create_adminToken_returns201_andMapsResponse() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sellerId").value(sellerId))
                .andExpect(jsonPath("$.data.sellerName").value("행복상회"))
                .andExpect(jsonPath("$.data.platform").value("COUPANG"));
    }
}
