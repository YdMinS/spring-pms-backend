package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.Platform;
import com.pms.domain.PlatformFixedCost;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformFixedCostRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고정비 카탈로그 · 채널 연결 API (FEATURE_2609_33 / PLAN 2609_33 D10).
 *
 * <p>비용 마스터는 다른 비용 화면과 같은 등급이라 전부 ADMIN 이다. 권한·중복·사용중 삭제·플랫폼 교차만
 * 검증한다 — 필드 검증은 프레임워크 몫이라 1건만 둔다.
 */
class FixedCostControllerTest extends BaseIntegrationTest {

    private static final String CATALOG = "/api/admin/fixed-costs";

    @Autowired private PlatformFixedCostRepository platformFixedCostRepository;
    @Autowired private MarketplaceAccountFixedCostRepository accountFixedCostRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private SellerRepository sellerRepository;

    private MarketplaceAccount coupangAccount;
    private PlatformFixedCost coupangItem;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        coupangAccount = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder()
                        .seller(seller).platform(Platform.COUPANG).accountAlias("메인").isActive(true).build());
        MarketplaceAccountFixture.saveCredential(credentialRepository, coupangAccount, "V1", null);

        coupangItem = platformFixedCostRepository.save(PlatformFixedCost.builder()
                .platform(Platform.COUPANG).name("판매자서비스이용료")
                .amount(new BigDecimal("55000")).thresholdAmount(new BigDecimal("1000000"))
                .active(true).build());
    }

    @AfterEach
    void cleanup() {
        accountFixedCostRepository.deleteAll();
        platformFixedCostRepository.deleteAll();
        credentialRepository.deleteAll();
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testListRequiresAdmin() throws Exception {
        mockMvc.perform(get(CATALOG)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(CATALOG).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(CATALOG).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("판매자서비스이용료"))
                .andExpect(jsonPath("$.data[0].thresholdAmount").value(1000000));
    }

    // ---- business rules ----

    /** 같은 플랫폼에 같은 이름을 또 만들면 409 — 카탈로그가 둘로 갈리면 어느 쪽이 기준인지 알 수 없다. */
    @Test
    void testCreateDuplicateName() throws Exception {
        mockMvc.perform(post(CATALOG)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"platform":"COUPANG","name":"판매자서비스이용료","amount":55000}
                                """))
                .andExpect(status().isConflict());
    }

    /** 연결이 남은 항목은 지울 수 없다(끄려면 active=false) — 지우면 과거 순이익 근거가 사라진다. */
    @Test
    void testDeleteInUse() throws Exception {
        accountFixedCostRepository.save(MarketplaceAccountFixedCost.builder()
                .marketplaceAccount(coupangAccount).platformFixedCost(coupangItem)
                .chargeMode(FixedCostChargeMode.AUTO).build());

        mockMvc.perform(delete(CATALOG + "/" + coupangItem.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    /** 🔴 다른 플랫폼 항목이 채널에 걸리면 목록(플랫폼별)과 집계(채널별)가 서로 다른 답을 낸다. */
    @Test
    void testPutRejectsOtherPlatformItem() throws Exception {
        PlatformFixedCost naverItem = platformFixedCostRepository.save(PlatformFixedCost.builder()
                .platform(Platform.NAVER).name("네이버이용료")
                .amount(new BigDecimal("30000")).thresholdAmount(new BigDecimal("1000000"))
                .active(true).build());

        mockMvc.perform(put(accountPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fixedCostId":%d,"chargeMode":"AUTO"}]}
                                """.formatted(naverItem.getId())))
                .andExpect(status().isBadRequest());
    }

    /** 같은 플랫폼 항목은 멱등 replace 로 저장되고, 실효 임계는 override 를 따른다. */
    @Test
    void testPutReplacesLinks() throws Exception {
        mockMvc.perform(put(accountPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fixedCostId":%d,"chargeMode":"ALWAYS",
                                           "thresholdOverride":5000000,"appliedFrom":"2026-09"}]}
                                """.formatted(coupangItem.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chargeMode").value("ALWAYS"))
                .andExpect(jsonPath("$.data[0].thresholdAmount").value(5000000))
                .andExpect(jsonPath("$.data[0].appliedFrom").value("2026-09"));

        mockMvc.perform(get(accountPath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /** 적용 구간은 달 단위다 — 형식이 어긋나면 400(필드 검증은 이 1건으로 충분하다). */
    @Test
    void testPutRejectsInvalidAppliedMonth() throws Exception {
        mockMvc.perform(put(accountPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fixedCostId":%d,"chargeMode":"AUTO","appliedFrom":"2026/09"}]}
                                """.formatted(coupangItem.getId())))
                .andExpect(status().isBadRequest());
    }

    private String accountPath() {
        return "/api/admin/marketplace-account/" + coupangAccount.getId() + "/fixed-costs";
    }
}
