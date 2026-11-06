package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.repository.LotteonAccountCredentialRepository;
import com.pms.repository.LotteonShippingConfigRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEATURE_2610_24 — the new LotteON ADMIN entrances: channel shipping settings (D42 · D65), the shipping fee policy
 * list (D43) and the category / commission imports (D10 · D46). {@link LotteonApiClient} is mocked (no live HTTP).
 */
class LotteonShippingConfigControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private LotteonAccountCredentialRepository lotteonCredentialRepository;
    @Autowired private LotteonShippingConfigRepository lotteonShippingConfigRepository;

    @MockBean private LotteonApiClient lotteonApiClient;

    private Long accountId;

    @BeforeEach
    void seed() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("롯데상회").businessRegistration("444-55-66666").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.LOTTEON).isActive(true).build());
        LotteonAccountCredential credential = lotteonCredentialRepository.save(LotteonAccountCredential.builder()
                .marketplaceAccount(account).apiKey("KEY-1").trGrpCd("SR").trNo("LO100").build());
        // mappedBy inverse: the same persistence context hands back this instance, so fill it like the fixture does.
        ReflectionTestUtils.setField(account, "lotteonCredential", credential);
        accountId = account.getId();
        given(lotteonApiClient.post(eq(LotteonPaths.FEE_POLICIES), any(), eq("KEY-1"))).willReturn(
                objectMapper.readTree("{\"returnCode\":\"0000\",\"data\":[{\"dvCstPolNo\":\"DLD1\","
                        + "\"dvCstPolNm\":\"기본 배송비\",\"dvCstTypCd\":\"DV_CST\",\"dvCstDvsCd\":\"C\","
                        + "\"dvCst\":\"3000.00\",\"cndlFreeStdAmt\":\"30000.00\",\"useYn\":\"Y\"},"
                        + "{\"dvCstPolNo\":\"DLD2\",\"dvCstPolNm\":\"추가\",\"dvCstTypCd\":\"ADTN_DV_CST\","
                        + "\"useYn\":\"Y\"}]}"));
    }

    @AfterEach
    void cleanup() {
        lotteonShippingConfigRepository.deleteAll();
        lotteonCredentialRepository.deleteAll();
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    private String base() {
        return "/api/admin/marketplace-account/" + accountId;
    }

    @Test
    void lotteonShippingConfig_noToken_returns401() throws Exception {
        mockMvc.perform(get(base() + "/lotteon-shipping-config")).andExpect(status().isUnauthorized());
    }

    @Test
    void lotteonShippingConfig_userToken_returns403() throws Exception {
        mockMvc.perform(put(base() + "/lotteon-shipping-config")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"settings\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertLotteonConfig_writesThePolicyFacts_andDropsClientAmounts() throws Exception {
        mockMvc.perform(put(base() + "/lotteon-shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(
                                "lotteon.owhpNo", "PLO1",
                                "lotteon.rtrpNo", "PLO2",
                                "lotteon.dvCstPolNo", "DLD1",
                                "lotteon.dvCst", "1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings['lotteon.dvCstDvsCd']").value("C"))
                .andExpect(jsonPath("$.data.settings['lotteon.dvCst']").value("3000"))
                .andExpect(jsonPath("$.data.settings['lotteon.cndlFreeStdAmt']").value("30000"))
                .andExpect(jsonPath("$.data.priceRecalcFailedCount").value(0));

        mockMvc.perform(get(base() + "/lotteon-shipping-config").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings['lotteon.owhpNo']").value("PLO1"));
    }

    @Test
    void upsertLotteonConfig_unknownPolicy_returns400() throws Exception {
        mockMvc.perform(put(base() + "/lotteon-shipping-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(
                                "lotteon.dvCstPolNo", "DLD2")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고른 배송비 정책이 롯데ON 에 없습니다"));
    }

    @Test
    void feePolicies_listsTheShippingFeePoliciesOnly() throws Exception {
        mockMvc.perform(get(base() + "/lotteon-shipping/fee-policies").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("DLD1"))
                .andExpect(jsonPath("$.data[0].name").value("기본 배송비"));
    }

    @Test
    void feePolicies_userToken_returns403() throws Exception {
        mockMvc.perform(get(base() + "/lotteon-shipping/fee-policies").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void categoryImport_userToken_returns403_andFeeWithoutTree_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/category-import/lotteon").param("accountId", String.valueOf(accountId))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        MockMultipartFile csv = new MockMultipartFile("file", "fee.csv", "text/csv",
                "depth,category_path,commission_pct_vat_incl\n1,\"가\",1.00\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/category-import/lotteon-fee").file(csv))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/admin/category-import/lotteon-fee").file(csv)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("롯데ON 카테고리 목록을 먼저 들여와야 합니다."));
    }
}
