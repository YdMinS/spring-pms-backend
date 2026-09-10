package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.SettlementPayoutRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 매출 집계 API — ADMIN 권한(401/403/200, PLAN D17) + 잘못된 기간 400.
 *
 * <p>전부 로컬 DB 집계라 마켓 목이 필요 없다 — 이 테스트가 도는 것 자체가 "조회에 쿠팡 왕복이 없다"는 확인이다.
 */
class SalesStatsControllerTest extends BaseIntegrationTest {

    private static final String SUMMARY = "/api/admin/sales/summary";
    private static final String BY_CHANNEL = "/api/admin/sales/by-channel";
    private static final String BY_PRODUCT = "/api/admin/sales/by-product";
    private static final String LINES = "/api/admin/sales/lines";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private SettlementPayoutRepository settlementPayoutRepository;

    private Long accountId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder()
                        .seller(seller).platform(Platform.COUPANG).accountAlias("메인").isActive(true).build());
        MarketplaceAccountFixture.saveCredential(credentialRepository, account, "V1", null);
        accountId = account.getId();

        // 🔴 판매일 기간과 상관없이 잡혀야 하는 "받을 돈"(PLAN D4) — 지급일은 조회 기간 밖으로 둔다.
        settlementPayoutRepository.save(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.WEEKLY)
                .revenueRecognitionMonth("2026-07")
                .settlementDate(LocalDate.of(2026, 7, 10))
                .finalAmount(new BigDecimal("1804000"))
                .status(SettlementPayoutStatus.SCHEDULED)
                .reconStatus(SettlementReconStatus.UNRECONCILED)
                .build());
    }

    @AfterEach
    void cleanup() {
        settlementPayoutRepository.deleteAll();
        credentialRepository.deleteAll();
        marketplaceAccountRepository.deleteAll();
        sellerRepository.deleteAll();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void salesEndpoints_noToken_return401() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void salesEndpoints_userToken_return403() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ---- happy paths ----

    /** 판매 실적이 없어도 "받을 돈"은 기간과 무관하게 잡힌다(PLAN D4). */
    @Test
    void summary_adminToken_returns200WithPendingPayout() throws Exception {
        mockMvc.perform(get(SUMMARY)
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sellerName").value("행복상회"))
                .andExpect(jsonPath("$.data[0].grossSales").value(0))
                // 판 것이 없으면 순이익 0 이 정직한 값이다 — 재료가 빠져 못 내는 null 과는 다르다.
                .andExpect(jsonPath("$.data[0].estNetProfit").value(0))
                .andExpect(jsonPath("$.data[0].costBasisReady").value(true))
                .andExpect(jsonPath("$.data[0].pendingPayout").value(1804000.00))
                // 🔴 대사 상태 건수는 응답에 없다(FEATURE_2609_34) — 기간이 안 걸리는 건수를 기간 행에
                //    실으면 "이 기간에 N건이 어긋났다"로 읽힌다. 대사는 인식월 정산 목록이 건별로 보여준다.
                .andExpect(jsonPath("$.data[0].unreconciledPayouts").doesNotExist());
    }

    @Test
    void byChannel_adminToken_returns200() throws Exception {
        mockMvc.perform(get(BY_CHANNEL).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountAlias").value("메인"))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].paidAmount").value(0))
                .andExpect(jsonPath("$.data[0].amountOnlyPayouts").doesNotExist())
                .andExpect(jsonPath("$.data[0].payoutCount").doesNotExist());
    }

    @Test
    void byProduct_adminToken_returns200() throws Exception {
        mockMvc.perform(get(BY_PRODUCT).param("crossChannel", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /** ④ 판매 내역 — 채널을 지정하면 그 채널의 주문 라인이 배열로 온다(판 것이 없으면 빈 배열). */
    @Test
    void lines_adminToken_returns200() throws Exception {
        mockMvc.perform(get(LINES)
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("accountId", String.valueOf(accountId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * 🔴 계정 없이 부르면 400 이다 — 접지 않은 목록이라 계정을 안 받으면 전 채널 라인이 통째로 나간다.
     */
    @Test
    void lines_withoutAccount_returns400() throws Exception {
        mockMvc.perform(get(LINES)
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    // ---- validation ----

    @Test
    void salesEndpoints_invertedRange_return400() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path)
                            .param("from", "2026-09-30").param("to", "2026-09-01")
                            // ⚠️ 판매 내역만 계정을 요구한다. 나머지 엔드포인트는 모르는 파라미터라 무시하므로
                            //    루프를 쪼개지 않고 함께 넘긴다 — 안 넘기면 판매 내역이 "기간이 뒤집혀서"가
                            //    아니라 "계정이 없어서" 400 이 되어 이 테스트가 아무것도 지키지 못한다.
                            .param("accountId", String.valueOf(accountId))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());
        }
    }

    private static List<String> paths() {
        return List.of(SUMMARY, BY_CHANNEL, BY_PRODUCT, LINES);
    }
}
