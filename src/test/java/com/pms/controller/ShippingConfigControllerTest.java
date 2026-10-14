package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ElevenstAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.CoupangShippingConfigRepository;
import com.pms.repository.ElevenstAccountCredentialRepository;
import com.pms.repository.ElevenstShippingConfigRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.Map;

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
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private CoupangShippingConfigRepository shippingConfigRepository;
    @Autowired private ElevenstAccountCredentialRepository elevenstCredentialRepository;
    @Autowired private ElevenstShippingConfigRepository elevenstShippingConfigRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long accountId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("메인")
                .isActive(true).build());
        accountId = account.getId();
        MarketplaceAccountFixture.saveCredential(credentialRepository, account, "V1", null);

        given(coupangApiClient.get(contains("shipping-place/outbound"), anyString(), any())).willReturn(
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
        elevenstShippingConfigRepository.deleteAll();
        elevenstCredentialRepository.deleteAll();
        credentialRepository.deleteAll();          // FK child first
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

    // FEATURE_2610_13 / D84: 11st channel settings = one map of elevenst.* keys; unknown keys are dropped.
    @Test
    void upsertElevenstConfig_adminToken_keepsOnlyElevenstKeys() throws Exception {
        Long elevenstId = seedElevenstAccount();

        mockMvc.perform(put("/api/admin/marketplace-account/" + elevenstId + "/elevenst-shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(
                                "elevenst.dlvCstInstBasiCd", "02",
                                "elevenst.dlvCst1", "3000",
                                "deliveryCharge", "2500")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings['elevenst.dlvCst1']").value("3000"))
                .andExpect(jsonPath("$.data.settings.deliveryCharge").doesNotExist())
                .andExpect(jsonPath("$.data.priceRecalcFailedCount").value(0));

        mockMvc.perform(get("/api/admin/marketplace-account/" + elevenstId + "/elevenst-shipping-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings['elevenst.dlvCstInstBasiCd']").value("02"));
    }

    @Test
    void getElevenstConfig_onCoupangAccount_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/marketplace-account/" + accountId + "/elevenst-shipping-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("11번가 판매채널이 아닙니다."));
    }

    private Long seedElevenstAccount() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("11번가상회").businessRegistration("222-33-44444").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.ELEVENST).isActive(true).build());
        elevenstCredentialRepository.save(ElevenstAccountCredential.builder()
                .marketplaceAccount(account).apiKey("KEY-1").build());
        return account.getId();
    }
}
