package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.domain.Seller;
import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.SettlementAdjustmentRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private static final String PAYOUT_SYNC = "/api/admin/settlement/payout/sync";
    private static final String PAYOUTS = "/api/admin/settlement/payouts";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private SettlementPayoutRepository settlementPayoutRepository;
    @Autowired private SettlementLineRepository settlementLineRepository;
    @Autowired private SettlementAdjustmentRepository settlementAdjustmentRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long accountId;
    private Long payoutId;

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

        // 지급 묶음 1건(WEEKLY) + 미분류 라인 1건 + 차감 1건 — 조회 API 의 200 경로용.
        SettlementPayout payout = settlementPayoutRepository.save(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.WEEKLY)
                .revenueRecognitionMonth("2026-08")
                .recognitionFrom(LocalDate.of(2026, 8, 1))
                .recognitionTo(LocalDate.of(2026, 8, 7))
                .settlementDate(LocalDate.of(2026, 9, 4))
                .totalSale(new BigDecimal("1000000"))
                .serviceFee(new BigDecimal("106000"))
                .finalAmount(new BigDecimal("880000"))
                .status(SettlementPayoutStatus.PAID)
                .reconStatus(SettlementReconStatus.UNRECONCILED)
                .build());
        payoutId = payout.getId();
        settlementLineRepository.save(SettlementLine.builder()
                .settlementPayout(payout).marketplaceAccount(account)
                .externalOrderId("O1").platformOptionId("V10").saleType(SaleType.SALE)
                .recognitionDate(LocalDate.of(2026, 8, 3))
                .saleAmount(new BigDecimal("100000")).serviceFee(new BigDecimal("10600"))
                .serviceFeeVat(new BigDecimal("1060")).settlementAmount(new BigDecimal("88340"))
                .build());
        settlementAdjustmentRepository.save(SettlementAdjustment.builder()
                .settlementPayout(payout)
                .adjustmentType(SettlementAdjustmentType.DEDUCTION)
                .amount(new BigDecimal("85000"))
                .build());
    }

    @AfterEach
    void cleanup() {
        settlementAdjustmentRepository.deleteAll();   // FK child first
        settlementLineRepository.deleteAll();
        settlementPayoutRepository.deleteAll();
        credentialRepository.deleteAll();
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

    // ---- 대사 조회 API (02) — 6 엔드포인트 401/403/200 ----

    @Test
    void reconEndpoints_noToken_return401() throws Exception {
        for (String path : reconPaths()) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(PAYOUT_SYNC)).andExpect(status().isUnauthorized());
    }

    @Test
    void reconEndpoints_userToken_return403() throws Exception {
        for (String path : reconPaths()) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post(PAYOUT_SYNC).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void payoutSync_adminToken_returns200() throws Exception {
        mockMvc.perform(post(PAYOUT_SYNC)
                        .param("accountId", String.valueOf(accountId))
                        .param("month", "2026-08")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts").value(1))
                .andExpect(jsonPath("$.data.payouts").value(0));
    }

    @Test
    void payoutSync_futureMonth_returns400() throws Exception {
        mockMvc.perform(post(PAYOUT_SYNC)
                        .param("accountId", String.valueOf(accountId))
                        .param("month", "2099-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payouts_adminToken_returnsSummaries() throws Exception {
        mockMvc.perform(get(PAYOUTS).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payoutId").value(payoutId))
                .andExpect(jsonPath("$.data[0].settlementType").value("WEEKLY"))
                .andExpect(jsonPath("$.data[0].lineCount").value(1));
    }

    @Test
    void payoutDetail_adminToken_carriesDeductionGuidance() throws Exception {
        // 🔴 "플랫폼 확인 필요" 문구는 서버가 소유한다 — 프론트가 사유를 지어내지 않게 응답에 있어야 한다(D13).
        mockMvc.perform(get(PAYOUTS + "/" + payoutId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adjustments[0].type").value("DEDUCTION"))
                .andExpect(jsonPath("$.data.adjustments[0].guidance", containsString("플랫폼")))
                .andExpect(jsonPath("$.data.unmatchedCount").value(1));
    }

    @Test
    void payoutLines_adminToken_filtersUnmatched() throws Exception {
        mockMvc.perform(get(PAYOUTS + "/" + payoutId + "/lines")
                        .param("unmatched", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].externalOrderId").value("O1"))
                .andExpect(jsonPath("$.data[0].unmatched").value(true));
    }

    @Test
    void payoutReport_adminToken_returnsBothBlocks() throws Exception {
        mockMvc.perform(get(PAYOUTS + "/" + payoutId + "/report")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockA.adjustmentTotal").value(-85000))
                .andExpect(jsonPath("$.data.blockA.finalAmount").value(880000))
                .andExpect(jsonPath("$.data.blockA.unmatchedCount").value(1))
                .andExpect(jsonPath("$.data.blockB").exists());
    }

    @Test
    void payoutExport_adminToken_returnsXlsxBytes() throws Exception {
        byte[] body = mockMvc.perform(get(PAYOUTS + "/" + payoutId + "/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(body).isNotEmpty();
    }

    @Test
    void payoutDetail_unknownId_returns400() throws Exception {
        mockMvc.perform(get(PAYOUTS + "/999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    private String[] reconPaths() {
        return new String[]{PAYOUTS, PAYOUTS + "/" + payoutId, PAYOUTS + "/" + payoutId + "/lines",
                PAYOUTS + "/" + payoutId + "/report", PAYOUTS + "/" + payoutId + "/export"};
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
