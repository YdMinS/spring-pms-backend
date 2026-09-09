package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
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
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정산 동기화 API — ADMIN 권한(401/403/200, PLAN D17) + 잘못된 기간 400 + 자격증명 미노출.
 *
 * {@link CoupangApiClient} 는 목이다(라이브 호출 없음) — 어댑터·서비스는 실제로 돈다.
 */
class SettlementControllerTest extends BaseIntegrationTest {

    private static final String SYNC = "/api/admin/settlement/sync";
    private static final String SYNC_PERIOD = "/api/admin/settlement/sync/period";
    private static final String TARGETS = "/api/admin/settlement/sync/targets";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long accountId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder()
                        .seller(seller).platform(Platform.COUPANG).accountAlias("메인").isActive(true).build());
        accountId = account.getId();
        MarketplaceAccountFixture.saveCredential(credentialRepository, account, "V1", null);

        // 빈 구간은 HTTP 200 + 빈 배열이다(에러 아님).
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn("{\"code\":200,\"message\":\"OK\",\"data\":[],\"hasNext\":false,\"nextToken\":\"\"}");
    }

    @AfterEach
    void cleanup() {
        credentialRepository.deleteAll();          // FK child first
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void sync_noToken_returns401() throws Exception {
        mockMvc.perform(post(SYNC)).andExpect(status().isUnauthorized());
    }

    @Test
    void sync_userToken_returns403() throws Exception {
        mockMvc.perform(post(SYNC).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncTargets_userToken_returns403() throws Exception {
        mockMvc.perform(get(TARGETS).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ---- happy paths ----

    @Test
    void sync_adminToken_returns200() throws Exception {
        mockMvc.perform(post(SYNC).param("accountId", String.valueOf(accountId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts").value(1))
                .andExpect(jsonPath("$.data.lines").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.skipped").value(false));
    }

    @Test
    void syncPeriod_invertedRange_returns400() throws Exception {
        mockMvc.perform(post(SYNC_PERIOD)
                        .param("accountId", String.valueOf(accountId))
                        .param("from", "2026-08-10")
                        .param("to", "2026-08-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncTargets_adminToken_hidesCredentials() throws Exception {
        mockMvc.perform(get(TARGETS).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountId").value(accountId))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].accessKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].secretKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].vendorId").doesNotExist());
    }
}
