package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
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
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
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
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final String BY_RECOGNITION = PAYOUTS + "/by-recognition";
    private static final String SUGGESTIONS = "/api/admin/settlement/commission-suggestions";
    private static final String SUGGESTIONS_APPLY = SUGGESTIONS + "/apply";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private SettlementPayoutRepository settlementPayoutRepository;
    @Autowired private SettlementLineRepository settlementLineRepository;
    @Autowired private SettlementAdjustmentRepository settlementAdjustmentRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long accountId;
    private Long payoutId;
    /** 06 의 제안 대상 = 실측 라인이 붙은 카테고리. */
    private Long platformCategoryId;

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

        seedCommissionFeedback(seller, account);
    }

    /**
     * 06 — 실측 수수료율 제안이 성립하려면 라인이 셀 옵션에 붙고, 그 셀이 카테고리 매핑을 타고
     * {@link PlatformCategory} 에 닿아야 한다(수수료 기준의 단일 출처).
     *
     * <p>기준표 5% vs 실측 10.6%(+ 부가세) 로 크게 벌려 둔다 — 0.1%p 문턱에 걸리지 않게.
     */
    private void seedCommissionFeedback(Seller seller, MarketplaceAccount account) {
        Category standard = categoryRepository.save(Category.builder().name("김치").build());
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("cat-kimchi").name("김치")
                .commissionRate(new BigDecimal("0.05")).build());
        platformCategoryId = platformCategory.getId();
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(standard).platform(Platform.COUPANG).platformCategoryId("cat-kimchi")
                .platformCategory(platformCategory).build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("행복 김치").active(true).category(standard).build());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .name("행복 김치 / 쿠팡").platform(Platform.COUPANG).seller(seller).masterProduct(master)
                .build());
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("1kg").sellingPrice(new BigDecimal("12000")).build());
        for (int i = 0; i < 5; i++) {
            settlementLineRepository.save(SettlementLine.builder()
                    .marketplaceAccount(account).productListingOption(option)
                    .externalOrderId("OC" + i).platformOptionId("VC" + i).saleType(SaleType.SALE)
                    .recognitionDate(LocalDate.of(2026, 8, 10))
                    .saleAmount(new BigDecimal("100000"))
                    .serviceFee(new BigDecimal("10600")).serviceFeeVat(new BigDecimal("1060"))
                    .settlementAmount(new BigDecimal("88340"))
                    .build());
        }
    }

    @AfterEach
    void cleanup() {
        settlementAdjustmentRepository.deleteAll();   // FK child first
        settlementLineRepository.deleteAll();
        settlementPayoutRepository.deleteAll();
        productListingOptionRepository.deleteAll();   // 06 시드 — 셀은 판매자를 참조한다
        productListingRepository.deleteAll();
        masterProductRepository.deleteAll();
        categoryMappingRepository.deleteAll();
        platformCategoryRepository.deleteAll();
        categoryRepository.deleteAll();
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
        return new String[]{PAYOUTS, BY_RECOGNITION, PAYOUTS + "/" + payoutId,
                PAYOUTS + "/" + payoutId + "/lines",
                PAYOUTS + "/" + payoutId + "/report", PAYOUTS + "/" + payoutId + "/export"};
    }

    // ---- 인식월 축 조회 (FEATURE_2609_34) ----

    /**
     * 🔴 축이 다르다는 것이 이 엔드포인트의 존재 이유다. 시드는 <b>인식월 2026-08 · 지급일 2026-09-04</b> —
     * 판매일 8월로 조회하면 나오고, 9월로 조회하면 나오지 않아야 한다. 여기가 뒤집히면 매출 화면이
     * "8월에 판 것을 9월에 받은" 정산을 8월 매출 옆에 못 보여준다.
     */
    @Test
    void payoutsByRecognition_matchesRecognitionMonthNotSettlementDate() throws Exception {
        mockMvc.perform(get(BY_RECOGNITION)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payoutId").value(payoutId))
                .andExpect(jsonPath("$.data[0].revenueRecognitionMonth").value("2026-08"))
                .andExpect(jsonPath("$.data[0].lineCount").value(1));

        mockMvc.perform(get(BY_RECOGNITION)
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** 여러 달을 조회하면 걸친 달이 전부 들어온다 — 화면이 월별로 묶을 수 있게 인식월 내림차순이다. */
    @Test
    void payoutsByRecognition_coversEveryMonthInThePeriod() throws Exception {
        mockMvc.perform(get(BY_RECOGNITION)
                        .param("from", "2026-07-15").param("to", "2026-08-20")
                        .param("accountId", String.valueOf(accountId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].revenueRecognitionMonth").value("2026-08"));
    }

    @Test
    void payoutsByRecognition_reversedPeriod_returns400() throws Exception {
        mockMvc.perform(get(BY_RECOGNITION)
                        .param("from", "2026-08-31").param("to", "2026-08-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    // ---- 실측 수수료율 피드백 (06) — 401/403/200 ----

    @Test
    void commissionSuggestions_noTokenOrUserToken_areRejected() throws Exception {
        mockMvc.perform(get(SUGGESTIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SUGGESTIONS_APPLY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(SUGGESTIONS_APPLY).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void commissionSuggestions_adminToken_returnsMeasuredGap() throws Exception {
        mockMvc.perform(get(SUGGESTIONS)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestions[0].platformCategoryId").value(platformCategoryId))
                .andExpect(jsonPath("$.data.suggestions[0].samples").value(5))
                // 실측 11.66% vs 기준 5.5%(부가세 포함 기준으로 맞춘 값)
                .andExpect(jsonPath("$.data.suggestions[0].measuredRatio").value(0.1166))
                .andExpect(jsonPath("$.data.suggestions[0].currentRatio").value(0.055))
                .andExpect(jsonPath("$.data.suggestions[0].suggestedRate").value(0.11));
    }

    @Test
    void commissionApply_adminToken_updatesRateAndKeepsPricesUntouched() throws Exception {
        BigDecimal before = productListingOptionRepository.findAll().get(0).getSellingPrice();

        mockMvc.perform(post(SUGGESTIONS_APPLY)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-08-01\",\"to\":\"2026-08-31\",\"items\":[{"
                                + "\"platformCategoryId\":" + platformCategoryId + ",\"newRate\":0.106}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.affectedListings").value(1));

        assertThat(platformCategoryRepository.findById(platformCategoryId).orElseThrow()
                .getCommissionRate()).isEqualByComparingTo("0.11");
        // 🔴 판매가는 그대로다 — 반영은 원가/가격 반영이 소유한다(PLAN 2609_28 D4).
        assertThat(productListingOptionRepository.findAll().get(0).getSellingPrice())
                .isEqualByComparingTo(before);
    }

    @Test
    void commissionApply_staleRate_returns409() throws Exception {
        mockMvc.perform(post(SUGGESTIONS_APPLY)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-08-01\",\"to\":\"2026-08-31\",\"items\":[{"
                                + "\"platformCategoryId\":" + platformCategoryId + ",\"newRate\":0.30}]}"))
                .andExpect(status().isConflict());
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
