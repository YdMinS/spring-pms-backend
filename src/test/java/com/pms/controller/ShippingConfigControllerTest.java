package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shipping config endpoints (FEATURE_2608_06 / 72): authority (401/403) + the four happy paths (outbound
 * lookup, return lookup, get config, upsert). {@link CoupangApiClient} is mocked (no live HTTP) — the real
 * provider/resolver/service still run.
 */
class ShippingConfigControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private MarketplaceShippingConfigRepository shippingConfigRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long accountId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("메인")
                .vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build());
        accountId = account.getId();

        given(coupangApiClient.get(contains("outboundShippingCenters"), anyString(), any())).willReturn(
                "{\"code\":200,\"data\":{\"content\":["
                        + "{\"outboundShippingPlaceCode\":\"74010\",\"shippingPlaceName\":\"기본출고지\"}]}}");
        given(coupangApiClient.get(contains("returnShippingCenters"), anyString(), any())).willReturn(
                "{\"code\":200,\"data\":{\"content\":[{"
                        + "\"returnCenterCode\":\"RC-1\",\"shippingPlaceName\":\"기본반품지\","
                        + "\"placeAddresses\":[{\"returnZipCode\":\"06000\",\"returnAddress\":\"서울시\"}]}]}}");
    }

    @AfterEach
    void cleanup() {
        shippingConfigRepository.deleteAll();
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void getConfig_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/shipping-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getConfig_userToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/shipping-config")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ---- happy paths ----

    @Test
    void outbound_adminToken_returns200WithPlaces() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/shipping-places/outbound")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("74010"))
                .andExpect(jsonPath("$.data[0].name").value("기본출고지"));
    }

    @Test
    void returnCenters_adminToken_returns200WithAddressBlock() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/shipping-places/return")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("RC-1"))
                .andExpect(jsonPath("$.data[0].zipCode").value("06000"));
    }

    @Test
    void getConfig_adminToken_returns200WithNullFieldsWhenUnset() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/shipping-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketplaceAccountId").value(accountId))
                .andExpect(jsonPath("$.data.outboundShippingPlaceCode").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void upsertConfig_adminToken_returns200AndPersists() throws Exception {
        ShippingConfigRequest req = ShippingConfigRequest.builder()
                .outboundShippingPlaceCode("74010")
                .returnCenterCode("RC-1")
                .deliveryCharge(new BigDecimal("2500"))
                .remoteAreaDeliverable("Y")
                .build();

        mockMvc.perform(put("/api/admin/marketplace-account/" + accountId + "/shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outboundShippingPlaceCode").value("74010"))
                .andExpect(jsonPath("$.data.remoteAreaDeliverable").value("Y"));
    }
}
