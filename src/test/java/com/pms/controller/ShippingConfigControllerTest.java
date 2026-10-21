package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ElevenstAccountCredential;
import com.pms.domain.EsmAccountCredential;
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
import com.pms.repository.EsmAccountCredentialRepository;
import com.pms.repository.EsmShippingConfigRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.esm.EsmApiClient;
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
    @Autowired private EsmAccountCredentialRepository esmCredentialRepository;
    @Autowired private EsmShippingConfigRepository esmShippingConfigRepository;

    @MockBean private CoupangApiClient coupangApiClient;
    @MockBean private EsmApiClient esmApiClient;

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
        esmShippingConfigRepository.deleteAll();
        esmCredentialRepository.deleteAll();
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

    // FEATURE_2610_26 / D66: the chosen bundle-fee policy is read from ESM (post 18) and its four facts are written by
    // the server — a client-sent policy key is dropped.
    @Test
    void upsertEsmConfig_writesThePolicyFactsFromEsm_andDropsClientPolicyKeys() throws Exception {
        Long esmId = seedEsmAccount();
        given(esmApiClient.get(org.mockito.ArgumentMatchers.eq("/item/v1/shipping/places/176042/policies"), any()))
                .willReturn(objectMapper.readTree("{\"placeNo\":176042,\"policies\":[{\"policyNo\":662995,"
                        + "\"feeType\":3,\"fee\":2500,\"isPrepayment\":true,\"isCashOnDelivery\":false,"
                        + "\"shippingFee\":[{\"condition\":20000}]}]}"));

        mockMvc.perform(put("/api/admin/marketplace-account/" + esmId + "/esm-shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(
                                "esm.placeNo", "176042",
                                "esm.bundlePolicyNo", "662995",
                                "esm.returnFee", "3000",
                                "esm.bundleFee", "1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings['esm.bundleFeeType']").value("3"))
                .andExpect(jsonPath("$.data.settings['esm.bundleFee']").value("2500"))
                .andExpect(jsonPath("$.data.settings['esm.bundleFreeOver']").value("20000"))
                .andExpect(jsonPath("$.data.settings['esm.bundlePrepay']").value("true"))
                .andExpect(jsonPath("$.data.settings['esm.returnFee']").value("3000"))
                .andExpect(jsonPath("$.data.priceRecalcFailedCount").value(0));
    }

    @Test
    void upsertEsmConfig_policyNotOfThePlace_returns400() throws Exception {
        Long esmId = seedEsmAccount();
        given(esmApiClient.get(org.mockito.ArgumentMatchers.eq("/item/v1/shipping/places/176042/policies"), any()))
                .willReturn(objectMapper.readTree("{\"placeNo\":176042,\"policies\":[]}"));

        mockMvc.perform(put("/api/admin/marketplace-account/" + esmId + "/esm-shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(
                                "esm.placeNo", "176042",
                                "esm.bundlePolicyNo", "662995")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고른 묶음배송비는 이 출하지의 정책이 아닙니다"));
    }

    private Long seedEsmAccount() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("지마켓상회").businessRegistration("333-44-55555").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.ESM).isActive(true).build());
        EsmAccountCredential credential = esmCredentialRepository.save(EsmAccountCredential.builder()
                .marketplaceAccount(account).masterId("master").secretKey("SECRET")
                .gmarketSellerId("g-id").auctionSellerId("a-id").build());
        // mappedBy inverse: the same persistence context hands back this instance, so fill it like the fixture does.
        org.springframework.test.util.ReflectionTestUtils.setField(account, "esmCredential", credential);
        return account.getId();
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
