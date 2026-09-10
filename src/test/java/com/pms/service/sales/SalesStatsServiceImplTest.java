package com.pms.service.sales;

import com.pms.domain.CarrierRate;
import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.PlatformFixedCost;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.MonthlyChannelSales;
import com.pms.dto.response.PayoutAggregate;
import com.pms.dto.response.ProductProfitResponse;
import com.pms.dto.response.SalesLineGroup;
import com.pms.dto.response.SellerSalesResponse;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 매출 집계 서비스 — 세율·순이익·축 변환 (FEATURE_2609_30 / PLAN D4 · D14 · D19 · 03).
 *
 * <p>🔴 이 클래스에 {@code ProductRepository} 목이 <b>없다</b>. 원가는 {@code order_line} 스냅샷뿐이고
 * 현재가로 메우면 원가를 고칠 때마다 과거 순이익이 소급 변동한다 — 의존 자체를 두지 않는 것이
 * {@code verify(productRepository, never())} 보다 강한 보증이다.
 *
 * <p>수량·할인 안분처럼 <b>SQL 안에 있는</b> 규칙은 목으로 검증되지 않는다 →
 * {@code OrderLineSalesAggregationTest}(@DataJpaTest) 가 그쪽을 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class SalesStatsServiceImplTest {

    private static final BigDecimal VAT = new BigDecimal("0.1");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private SettlementPayoutRepository settlementPayoutRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private MarketplaceAccountFixedCostRepository fixedCostRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    private SalesStatsServiceImpl service;

    private final Seller seller = Seller.builder().id(1L).sellerName("셀러A").build();
    private final MarketplaceAccount coupang = account(10L, "쿠팡-메인");
    private final MarketplaceAccount naver = account(20L, "네이버-메인");

    @BeforeEach
    void setUp() {
        // 🔴 계산기는 목이 아니라 실제 구현이다 — 판정 규칙이 갈리면 두 탭 합계가 어긋난다.
        service = new SalesStatsServiceImpl(orderLineRepository, productListingOptionRepository,
                settlementPayoutRepository, marketplaceAccountRepository, fixedCostRepository,
                masterChannelConfigService, new FixedCostCalculator(), VAT);
    }

    /** 🔴 D19: 쿠팡은 수수료 + 그 수수료의 부가세를 뗀다. 10.6% × 1.1 = 11.66% 가 추정 수수료다. */
    @Test
    void estFeeIncludesFeeVat() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 0));
        givenOption(100L, 500L, "0.106", "2500", "500");

        assertThat(service.summary(FROM, TO, null)).singleElement()
                .extracting(SellerSalesResponse::estFee)
                .satisfies(fee -> assertThat((BigDecimal) fee).isEqualByComparingTo("11660"));
    }

    /**
     * 🔴 원가 스냅샷이 없는 라인이 하나라도 섞이면 순이익을 <b>내지 않는다</b>. {@code Product.price} 로
     * 메우면 원가를 고칠 때마다 과거 손익이 소급 변동하고, 그런 리포트는 아무도 믿지 않는다.
     */
    @Test
    void estNetProfitNullWhenSnapshotMissing() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 1));
        givenOption(100L, 500L, "0.106", "2500", "500");

        assertThat(service.summary(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.estNetProfit()).isNull();
                    assertThat(row.costBasisReady()).isFalse();
                });
    }

    /** 스냅샷이 전부 있으면 매출 − 원가 − 추정수수료(VAT 포함) − 배송비 − 상자비. */
    @Test
    void estNetProfitSubtractsCostFeeAndShipping() {
        givenAccounts(coupang);
        givenNoPayouts();
        // 10개 · 매출 100,000 · 원가 40,000 · 수수료 11,660 · (배송 2,500 + 상자 500) × 10 = 30,000
        givenSales(group(coupang, 100L, 100L, "100000", "0", "40000", 0, 10L));
        givenOption(100L, 500L, "0.106", "2500", "500");

        assertThat(service.summary(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.costBasisReady()).isTrue();
                    assertThat(row.estNetProfit()).isEqualByComparingTo("18340");
                });
    }

    /** 🔴 D4: "받을 돈"은 판매일 기간과 축이 달라 기간을 좁혀도 그대로다. */
    @Test
    void pendingPayoutIgnoresPeriodFilter() {
        givenAccounts(coupang);
        given(settlementPayoutRepository.aggregateByAccount(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(new PayoutAggregate(10L, new BigDecimal("1804000"),
                        BigDecimal.ZERO, 2L, 1L, 5L)));
        given(orderLineRepository.aggregateSales(any(), any(), any())).willReturn(List.of());

        SellerSalesResponse wide = service.summary(FROM, TO, null).get(0);
        SellerSalesResponse narrow = service.summary(TO, TO, null).get(0);

        assertThat(wide.pendingPayout()).isEqualByComparingTo("1804000");
        assertThat(narrow.pendingPayout()).isEqualByComparingTo(wide.pendingPayout());
        assertThat(narrow.unreconciledPayouts()).isEqualTo(2L);
    }

    /** 현금주의({@code paidAmount})·미분류 배지는 채널 레벨에만 있다(D4-1 · D5-5). */
    @Test
    void channelRowCarriesCashBasisAndBadges() {
        givenAccounts(coupang);
        given(settlementPayoutRepository.aggregateByAccount(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(new PayoutAggregate(10L, new BigDecimal("1804000"),
                        new BigDecimal("900000"), 2L, 1L, 5L)));
        given(orderLineRepository.aggregateSales(any(), any(), any())).willReturn(List.of());

        assertThat(service.byChannel(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.paidAmount()).isEqualByComparingTo("900000");
                    assertThat(row.pendingPayout()).isEqualByComparingTo("1804000");
                    assertThat(row.amountOnlyPayouts()).isEqualTo(1L);
                    assertThat(row.platform()).isEqualTo(Platform.COUPANG);
                    assertThat(row.payoutCount()).isEqualTo(5L);
                });
    }

    /**
     * 🔴 정산 묶음이 하나도 없는 채널은 {@code payoutCount = 0} 이어야 한다.
     *
     * <p>없으면 화면이 "금액 일치"(전부 맞음)와 "정산 이력 없음"(아직 안 들어옴)을 구분하지 못한다 —
     * 둘 다 {@code unreconciledPayouts = 0} 이라 정산 전 채널에 초록 배지가 뜬다.
     * 집계 쿼리는 {@code group by} 라 묶음이 없는 계정의 행을 아예 내주지 않으므로, 그 빈자리를
     * {@link PayoutAggregate#empty} 가 0 으로 메운다.
     */
    @Test
    void channelWithNoPayoutsReportsZeroCount() {
        givenAccounts(coupang);
        given(settlementPayoutRepository.aggregateByAccount(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of());
        given(orderLineRepository.aggregateSales(any(), any(), any())).willReturn(List.of());

        assertThat(service.byChannel(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.payoutCount()).isZero();
                    // 정산이 없다는 것과 차이가 없다는 것은 다르다 — 둘을 같은 값으로 뭉개면 안 된다.
                    assertThat(row.unreconciledPayouts()).isZero();
                    assertThat(row.pendingPayout()).isEqualByComparingTo("0");
                });
    }

    /** 같은 마스터가 두 계정에 걸리면 cross=true 는 1행, cross=false 는 2행이다(집계 경로는 하나). */
    @Test
    void byProductCrossChannelMergesAccounts() {
        givenAccounts(coupang, naver);
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 1),
                group(naver, 200L, 100L, "60000", "0", "0", 1));
        givenOptions(option(100L, 500L), option(200L, 500L));
        givenCommissionAndShipping("0.106", "2500", "500");

        List<ProductProfitResponse> merged = service.byProduct(FROM, TO, null, true);
        List<ProductProfitResponse> split = service.byProduct(FROM, TO, null, false);

        assertThat(merged).singleElement()
                .satisfies(row -> {
                    assertThat(row.grossSales()).isEqualByComparingTo("160000");
                    assertThat(row.accountId()).isNull();
                });
        assertThat(split).hasSize(2)
                .extracting(ProductProfitResponse::accountId, ProductProfitResponse::accountAlias)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(10L, "쿠팡-메인"),
                        org.assertj.core.api.Assertions.tuple(20L, "네이버-메인"));
    }

    /**
     * 🔴 채널 옵션이 없는 라인은 버리지 않고 `미분류` 한 행으로 남고, <b>합계가 판매자 요약(①)과 일치</b>한다.
     * 여기서 어긋나면 화면이 두 숫자를 동시에 보여줄 수 없다.
     */
    @Test
    void unmappedLinesGoToUncategorizedRow() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "5000", "0", 1),
                unmappedGroup(coupang, "30000", "1000"));
        givenOption(100L, 500L, "0.106", "2500", "500");

        List<ProductProfitResponse> products = service.byProduct(FROM, TO, null, true);
        SellerSalesResponse summary = service.summary(FROM, TO, null).get(0);

        assertThat(products).hasSize(2);
        assertThat(products).filteredOn(ProductProfitResponse::uncategorized).singleElement()
                .satisfies(row -> {
                    assertThat(row.masterProductId()).isNull();
                    assertThat(row.masterProductName()).isEqualTo("미분류");
                    assertThat(row.grossSales()).isEqualByComparingTo("30000");
                });
        assertThat(sum(products, ProductProfitResponse::grossSales))
                .isEqualByComparingTo(summary.grossSales());
        assertThat(sum(products, ProductProfitResponse::discount))
                .isEqualByComparingTo(summary.discount());
        assertThat(products.stream().mapToLong(ProductProfitResponse::netQty).sum())
                .isEqualTo(summary.netQty());
    }

    /** 카테고리 매핑이 없는 셀 하나 때문에 화면 전체가 500 이 되면 안 된다 — 그 그룹만 손익을 포기한다. */
    @Test
    void missingCommissionBaseDoesNotFailTheReport() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 0));
        givenOptions(option(100L, 500L));
        given(masterChannelConfigService.resolvePlatformCategory(any()))
                .willThrow(new IllegalArgumentException("카테고리 매핑 없음"));

        assertThat(service.summary(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.grossSales()).isEqualByComparingTo("100000");
                    assertThat(row.estNetProfit()).isNull();
                    assertThat(row.costBasisReady()).isFalse();
                });
    }

    @Test
    void rejectsInvertedPeriod() {
        assertThatThrownBy(() -> service.summary(TO, FROM, null))
                .isInstanceOf(IllegalArgumentException.class);
    }


    // ── 고정비 (FEATURE_2609_33 / PLAN 2609_33 D6 · D12) ──────────────────

    /** 임계를 넘은 달의 고정비는 채널 순이익에서 빠지고, 그 값이 자기 필드로도 내려간다. */
    @Test
    void testByChannelSubtractsFixedCost() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "40000", 0, 10L));
        givenOption(100L, 500L, "0.106", "2500", "500");
        givenFixedCostLinks(link(coupang));
        givenMonthlySales(monthlySales(coupang, 9, "2000000"));

        assertThat(service.byChannel(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.fixedCost()).isEqualByComparingTo("55000");
                    assertThat(row.fixedCostMonths()).isEqualTo(1);
                    // 고정비 없을 때의 순이익 18,340 에서 55,000 을 뺀 값.
                    assertThat(row.estNetProfit()).isEqualByComparingTo("-36660");
                });
    }

    /**
     * 🔴 D6 의 방어선: 원가가 확정되지 않아 순이익이 {@code null} 이어도 <b>고정비는 그대로 내려간다</b>.
     *
     * <p>{@code estNetProfit} 에 녹이면 원가 미확정 채널에서 고정비가 통째로 사라져 화면이 "고정비 0" 으로 읽는다.
     */
    @Test
    void testByChannelKeepsFixedCostWhenProfitNull() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 1));
        givenOption(100L, 500L, "0.106", "2500", "500");
        givenFixedCostLinks(link(coupang));
        givenMonthlySales(monthlySales(coupang, 9, "2000000"));

        assertThat(service.byChannel(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.costBasisReady()).isFalse();
                    assertThat(row.estNetProfit()).isNull();
                    assertThat(row.fixedCost()).isEqualByComparingTo("55000");
                });
    }

    /**
     * 고정비를 쓰지 않는 채널은 0 이고 순이익이 종전과 같다.
     *
     * <p>🔴 연결이 0건이면 <b>월별 집계 쿼리를 아예 부르지 않는다</b> — 고정비를 안 쓰는 테넌트가
     * 매출 화면을 열 때마다 집계를 한 번 더 돌 이유가 없다.
     */
    @Test
    void testByChannelWithoutLinkIsZero() {
        givenAccounts(coupang);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "40000", 0, 10L));
        givenOption(100L, 500L, "0.106", "2500", "500");

        assertThat(service.byChannel(FROM, TO, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.fixedCost()).isEqualByComparingTo("0");
                    assertThat(row.fixedCostMonths()).isZero();
                    assertThat(row.estNetProfit()).isEqualByComparingTo("18340");
                });
        verify(orderLineRepository, never()).aggregateMonthlySales(any(), any(), any());
    }

    /** 🔴 D12: 판매자 행은 채널 결과의 <b>합</b>이다 — 판매자 단위로 임계를 다시 판정하지 않는다. */
    @Test
    void testSummarySumsChannelFixedCost() {
        givenAccounts(coupang, naver);
        givenNoPayouts();
        givenSales(group(coupang, 100L, 100L, "100000", "0", "0", 0),
                group(naver, 200L, 100L, "60000", "0", "0", 0));
        givenOptions(option(100L, 500L), option(200L, 500L));
        givenCommissionAndShipping("0.106", "2500", "500");
        givenFixedCostLinks(link(coupang), link(naver));
        givenMonthlySales(monthlySales(coupang, 9, "2000000"), monthlySales(naver, 9, "2000000"));

        assertThat(service.summary(FROM, TO, null)).singleElement()
                .extracting(SellerSalesResponse::fixedCost)
                .satisfies(fixedCost -> assertThat((BigDecimal) fixedCost).isEqualByComparingTo("110000"));
    }

    // ------------------------------------------------------------- fixtures


    private void givenFixedCostLinks(MarketplaceAccountFixedCost... links) {
        given(fixedCostRepository.findByMarketplaceAccount_IdIn(any())).willReturn(List.of(links));
    }

    private void givenMonthlySales(MonthlyChannelSales... rows) {
        given(orderLineRepository.aggregateMonthlySales(any(), any(), any())).willReturn(List.of(rows));
    }

    /** 쿠팡 판매자서비스이용료 미러: 55,000원 · 임계 1,000,000원 · AUTO. */
    private MarketplaceAccountFixedCost link(MarketplaceAccount account) {
        return MarketplaceAccountFixedCost.builder()
                .marketplaceAccount(account)
                .platformFixedCost(PlatformFixedCost.builder()
                        .platform(Platform.COUPANG)
                        .name("판매자서비스이용료")
                        .amount(new BigDecimal("55000"))
                        .thresholdAmount(new BigDecimal("1000000"))
                        .active(true)
                        .build())
                .chargeMode(FixedCostChargeMode.AUTO)
                .build();
    }

    private MonthlyChannelSales monthlySales(MarketplaceAccount account, int month, String netSales) {
        return new MonthlyChannelSales(account.getId(), 2026, month, new BigDecimal(netSales));
    }

    private static BigDecimal sum(List<ProductProfitResponse> rows,
                                  java.util.function.Function<ProductProfitResponse, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private MarketplaceAccount account(Long id, String alias) {
        return MarketplaceAccountFixture.coupangStubBuilder()
                .id(id).seller(seller).platform(Platform.COUPANG).accountAlias(alias).build();
    }

    private void givenAccounts(MarketplaceAccount... accounts) {
        given(marketplaceAccountRepository.findAllWithSeller(any())).willReturn(List.of(accounts));
    }

    private void givenNoPayouts() {
        given(settlementPayoutRepository.aggregateByAccount(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of());
    }

    private void givenSales(SalesLineGroup... groups) {
        given(orderLineRepository.aggregateSales(any(), any(), any())).willReturn(List.of(groups));
    }

    private void givenOption(Long optionId, Long cellId, String commissionRate,
                             String delivery, String box) {
        givenOptions(option(optionId, cellId));
        givenCommissionAndShipping(commissionRate, delivery, box);
    }

    private void givenOptions(ProductListingOption... options) {
        given(productListingOptionRepository.findWithConfigByIdIn(any())).willReturn(List.of(options));
    }

    private void givenCommissionAndShipping(String commissionRate, String delivery, String box) {
        given(masterChannelConfigService.resolvePlatformCategory(any()))
                .willReturn(PlatformCategory.builder().commissionRate(new BigDecimal(commissionRate)).build());
        given(masterChannelConfigService.resolveDelivery(any(), any()))
                .willReturn(CarrierRate.builder().cost(new BigDecimal(delivery)).build());
        given(masterChannelConfigService.resolvePackage(any(), any()))
                .willReturn(Package.builder().cost(new BigDecimal(box)).build());
    }

    private ProductListingOption option(Long optionId, Long cellId) {
        MasterProduct master = MasterProduct.builder().id(500L).name("양말세트").active(true).build();
        return ProductListingOption.builder()
                .id(optionId)
                .productListing(ProductListing.builder()
                        .id(cellId).seller(seller).platform(Platform.COUPANG)
                        .name("양말세트").masterProduct(master).build())
                .masterProductOption(MasterProductOption.builder()
                        .id(optionId + 1).masterProduct(master).name("옵션A").build())
                .optionName("옵션A").sellingPrice(new BigDecimal("10000")).build();
    }

    private SalesLineGroup group(MarketplaceAccount account, Long optionId, Long masterId,
                                 String gross, String discount, String cost, long missingCostLines) {
        return group(account, optionId, masterId, gross, discount, cost, missingCostLines, 1L);
    }

    private SalesLineGroup group(MarketplaceAccount account, Long optionId, Long masterId,
                                 String gross, String discount, String cost, long missingCostLines,
                                 long netQty) {
        return new SalesLineGroup(seller.getId(), seller.getSellerName(), account.getId(),
                optionId, masterId, "양말세트", netQty, 0L,
                new BigDecimal(gross), new BigDecimal(discount), new BigDecimal(cost), missingCostLines);
    }

    /** 채널 옵션 연결이 없는 그룹(백필 누락·WING 수정분). */
    private SalesLineGroup unmappedGroup(MarketplaceAccount account, String gross, String discount) {
        return new SalesLineGroup(seller.getId(), seller.getSellerName(), account.getId(),
                null, null, null, 2L, 0L,
                new BigDecimal(gross), new BigDecimal(discount), BigDecimal.ZERO, 2L);
    }
}
