package com.pms.service.price;

import com.pms.domain.CarrierRate;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.dto.response.RepricingCandidatesResponse.Exclusion;
import com.pms.dto.response.RepricingCandidatesResponse.Row;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.ListingChannelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 마진 경보 조회(FEATURE_2609_39 / 01).
 *
 * <p>⚠️ {@link PriceCalculator} 는 <b>실물</b>을 쓴다(리포지토리·리졸버만 mock). 공식을 mock 으로 대체하면
 * "마진액 400 원이 기준 1000 원을 밑돈다"는 이 기능의 유일한 판정이 검증되지 않고, D16(셀당 1회 해석)도
 * 카테고리·마진 프리셋 조회 횟수로만 확인할 수 있다.</p>
 *
 * <p>고정 수치: 원가 5000 + 택배 3000 + 박스 500 = 8500, 수수료율 0.10 × VAT 1.1 = 실효 0.11.
 * 판정가 10000 → 수수료 1100, 마진액 400, 마진율 0.0400.</p>
 */
@ExtendWith(MockitoExtension.class)
class RepricingServiceTest {

    private static final Long SELLER_ID = 7L;
    private static final Long CELL_ID = 100L;
    private static final BigDecimal VAT = new BigDecimal("0.1");

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CellBomResolver cellBomResolver;
    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingChannelResolver channelResolver;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private PriceHistoryRecorder priceHistoryRecorder;

    private RepricingServiceImpl service;

    @BeforeEach
    void setUp() {
        PriceCalculator priceCalculator =
                new PriceCalculator(marginPolicyRepository, masterChannelConfigService, VAT);
        // 실행(02) 협력자는 조회 경로에서 한 번도 쓰이지 않는다 — mock 을 넘기되 어떤 스텁도 두지 않는다.
        service = new RepricingServiceImpl(productListingRepository, productListingOptionRepository,
                cellBomResolver, priceCalculator, listingAssetService, channelResolver,
                marketplaceAccountRepository, priceHistoryRecorder);
    }

    // ---------------------------------------------------------------- fixtures

    private final MasterProduct master = MasterProduct.builder().id(1L).name("마스터").active(true).build();
    private final Seller seller = Seller.builder().id(SELLER_ID).sellerName("행복상회").build();

    private ProductListing cell(Long id) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("쿠팡 셀")
                .status(ListingStatus.SELLING).platformProductId("SP-" + id)
                .seller(seller).masterProduct(master).build();
    }

    private MasterProductOption masterOption(Long id) {
        return MasterProductOption.builder().id(id).masterProduct(master).name("옵션" + id).build();
    }

    /** 대상 옵션 1건 — 식별자 있고 AUTO 가격. {@code marketPrice} 는 호출부가 정한다. */
    private ProductListingOption option(Long id, ProductListing cell, MasterProductOption masterOption,
                                        String sellingPrice, String marketPrice) {
        return ProductListingOption.builder().id(id).productListing(cell).masterProductOption(masterOption)
                .optionName("옵션" + id).platformOptionId("V-" + id)
                .sellingPrice(new BigDecimal(sellingPrice))
                .marketPrice(marketPrice == null ? null : new BigDecimal(marketPrice))
                .build();
    }

    /** BOM 1줄 = 5000 원짜리 물품 1개 (2609_71: 마스터를 타고 온다). */
    private CellBomResolver.Bom bom() {
        return CellBomResolver.Bom.of(List.of(new CellBomResolver.Line(1L,
                Product.builder().id(1L).productName("생수").price(new BigDecimal("5000")).build(), 1)));
    }

    /** optionId → BOM. 채널 전용 옵션은 여기 들어가지 않는다. */
    private Map<Long, CellBomResolver.Bom> boms(List<ProductListingOption> options) {
        Map<Long, CellBomResolver.Bom> map = new LinkedHashMap<>();
        options.forEach(option -> map.put(option.getId(), bom()));
        return map;
    }

    /** 셀·옵션·BOM 로드와 마진 프리셋을 한 번에 세운다. 기준값은 테스트마다 다르다. */
    private void givenCandidates(MarginPolicy policy, List<ProductListing> cells,
                                 List<ProductListingOption> options) {
        given(productListingRepository.findRepricingTargets(null, null)).willReturn(cells);
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(options);
        given(productListingOptionRepository.findWithConfigByIdIn(any())).willReturn(options);
        given(cellBomResolver.forOptions(anyCollection())).willReturn(boms(options));
        lenient().when(marginPolicyRepository.findBySellerIdAndPlatform(SELLER_ID, Platform.COUPANG))
                .thenReturn(Optional.of(policy));
    }

    /** 수수료 0.10 · 택배 3000 · 박스 500 — 이 셀은 계산 가능한 셀이다. */
    private void givenPricingConfig(ProductListing cell, MasterProductOption... masterOptions) {
        lenient().when(masterChannelConfigService.resolvePlatformCategory(cell))
                .thenReturn(PlatformCategory.builder().id(3L).platform(Platform.COUPANG).code("cat-1")
                        .commissionRate(new BigDecimal("0.10")).build());
        for (MasterProductOption masterOption : masterOptions) {
            lenient().when(masterChannelConfigService.resolveDelivery(cell, masterOption))
                    .thenReturn(CarrierRate.builder().cost(new BigDecimal("3000")).build());
            lenient().when(masterChannelConfigService.resolvePackage(cell, masterOption))
                    .thenReturn(Package.builder().cost(new BigDecimal("500")).build());
        }
    }

    private MarginPolicy policy(String minAmount, String minRate) {
        return MarginPolicy.builder().seller(seller).platform(Platform.COUPANG)
                .marginRate(new BigDecimal("0.1500"))
                .minMarginAmount(minAmount == null ? null : new BigDecimal(minAmount))
                .minMarginRate(minRate == null ? null : new BigDecimal(minRate))
                .build();
    }

    // ---------------------------------------------------------------- tests

    // 1. 금액 기준만 설정 — 마진액 400 < 1000 → 대응 필요.
    @Test
    void testCandidatesBelowThresholdByAmount() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        givenCandidates(policy("1000", null), List.of(cell), List.of(option(50L, cell, mo, "10000", "10000")));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.BELOW);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.below()).isTrue();
            assertThat(row.judgedPrice()).isEqualByComparingTo("10000");
            assertThat(row.costSum()).isEqualByComparingTo("5000");
            assertThat(row.delivery()).isEqualByComparingTo("3000");
            assertThat(row.box()).isEqualByComparingTo("500");
            assertThat(row.feeAmount()).isEqualByComparingTo("1100");
            assertThat(row.marginAmount()).isEqualByComparingTo("400");
            assertThat(row.marginRate()).isEqualByComparingTo("0.0400");
            assertThat(row.excluded()).isNull();
        });
        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.sellerName()).isEqualTo("행복상회");
            assertThat(group.optionCount()).isEqualTo(1);
            assertThat(group.belowCount()).isEqualTo(1);
            assertThat(group.belowManualCount()).isZero();
            assertThat(group.targetMarginRate()).isEqualByComparingTo("0.1500");
        });
    }

    // 2. 비율 기준만 설정 — 마진율 0.04 < 0.109 → 대응 필요(금액 기준이 없어도 걸린다).
    @Test
    void testCandidatesBelowThresholdByRate() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        givenCandidates(policy(null, "0.1090"), List.of(cell), List.of(option(50L, cell, mo, "10000", "10000")));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, null);

        assertThat(response.rows()).singleElement()
                .extracting(Row::below).isEqualTo(true);
        assertThat(response.groups().get(0).belowCount()).isEqualTo(1);
    }

    // 3. 기준이 둘 다 null = 미사용 — 마진이 음수여도 경보하지 않는다(기본선을 지어내지 않는다).
    @Test
    void testCandidatesNoThresholdNeverBelow() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        // 판정가 5000 → 수수료 550, 마진액 5000 − 8500 − 550 = −4050 (음수)
        givenCandidates(policy(null, null), List.of(cell), List.of(option(50L, cell, mo, "5000", "5000")));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.marginAmount()).isEqualByComparingTo("-4050");
            assertThat(row.below()).isFalse();
        });
        assertThat(response.groups().get(0).belowCount()).isZero();
    }

    // 4. D15: market_price 가 있으면 그 값으로 판정한다(로컬 selling_price 가 아니라).
    @Test
    void testCandidatesUsesMarketPriceOverSellingPrice() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        // 로컬은 이미 20000 으로 재계산됐지만 마켓에는 아직 10000 이 걸려 있다.
        givenCandidates(policy("1000", null), List.of(cell), List.of(option(50L, cell, mo, "20000", "10000")));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.judgedPrice()).isEqualByComparingTo("10000");
            assertThat(row.marginAmount()).isEqualByComparingTo("400");   // 20000 이었다면 마진은 훨씬 컸다
            assertThat(row.below()).isTrue();
            assertThat(row.pendingPush()).isTrue();                        // 두 값이 다르다 = 아직 안 밀림
        });
        assertThat(response.groups().get(0).pendingPushCount()).isEqualTo(1);
    }

    // 5. 2609_43 D1: 직접 지정가도 똑같이 판정하고 행도 내려보낸다. belowCount 는 그 행을 <b>포함</b>하고
    //    (화면의 행 수와 맞아야 한다), belowManualCount 는 그중 가격을 사람이 소유한 수를 겹쳐 센다.
    @Test
    void candidates_belowCountIncludesManual() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        ProductListingOption manual = option(50L, cell, mo, "10000", "10000").toBuilder()
                .priceSource(GeneratedContentSource.MANUAL_OVERRIDE).build();
        givenCandidates(policy("1000", null), List.of(cell), List.of(manual));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.BELOW);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.below()).isTrue();                       // 경보는 한다
            assertThat(row.excluded()).isEqualTo(Exclusion.MANUAL);  // 공식 재계산에서만 빠진다(D2)
        });
        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.belowCount()).isEqualTo(1);             // 🔴 2609_43: 직접 지정가도 대응 필요에 든다
            assertThat(group.belowManualCount()).isEqualTo(1);       // 그중 사람이 소유한 수(부분집합)
        });
    }

    // 6. D6: 마켓 식별자가 없는 옵션은 밀 곳이 없다 → 행 자체가 없다.
    //    (셀 상태·플랫폼 필터는 findRepricingTargets 쿼리가 소유한다 — ProductListingRepositoryRepricingTest 가 본다.)
    @Test
    void testCandidatesSkipsNonSellingAndUnregistered() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        ProductListingOption registered = option(50L, cell, mo, "10000", "10000");
        ProductListingOption unregistered = option(51L, cell, mo, "10000", "10000").toBuilder()
                .platformOptionId(null).build();

        given(productListingRepository.findRepricingTargets(null, null)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(registered, unregistered));
        // 식별자 없는 옵션은 동반 로드 대상에서 이미 빠져 있어야 한다.
        given(productListingOptionRepository.findWithConfigByIdIn(List.of(50L))).willReturn(List.of(registered));
        given(cellBomResolver.forOptions(anyCollection())).willReturn(boms(List.of(registered)));
        given(marginPolicyRepository.findBySellerIdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(policy("1000", null)));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).extracting(Row::optionId).containsExactly(50L);
        assertThat(response.groups().get(0).optionCount()).isEqualTo(1);
    }

    // 7. D15: market_price 가 없으면 「알 수 없음」이다 — 「아직 안 밀림」으로 세지 않는다.
    @Test
    void testCandidatesPendingPushFalseWhenMarketPriceNull() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo = masterOption(10L);
        givenCandidates(policy("1000", null), List.of(cell), List.of(option(50L, cell, mo, "10000", null)));
        givenPricingConfig(cell, mo);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.pendingPush()).isFalse();
            assertThat(row.judgedPrice()).isEqualByComparingTo("10000");   // selling_price 로 판정
        });
        assertThat(response.groups().get(0).pendingPushCount()).isZero();
    }

    // 8. 수수료 미설정 셀 하나가 목록 전체를 죽이면 안 된다 — 그 행만 UNCALCULABLE.
    @Test
    void testCandidatesUncalculableRowDoesNotFailRequest() {
        ProductListing good = cell(CELL_ID);
        ProductListing broken = cell(200L);
        MasterProductOption mo = masterOption(10L);
        givenCandidates(policy("1000", null), List.of(good, broken),
                List.of(option(50L, good, mo, "10000", "10000"), option(51L, broken, mo, "10000", "10000")));
        givenPricingConfig(good, mo);
        given(masterChannelConfigService.resolvePlatformCategory(broken))
                .willThrow(new IllegalArgumentException("수수료 미설정 — 카테고리 시드 필요"));

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).hasSize(2);
        Row brokenRow = response.rows().stream().filter(r -> r.optionId().equals(51L)).findFirst().orElseThrow();
        assertThat(brokenRow.excluded()).isEqualTo(Exclusion.UNCALCULABLE);
        assertThat(brokenRow.excludedReason()).contains("수수료 미설정");
        assertThat(brokenRow.below()).isFalse();
        assertThat(brokenRow.marginAmount()).isNull();

        Row goodRow = response.rows().stream().filter(r -> r.optionId().equals(50L)).findFirst().orElseThrow();
        assertThat(goodRow.marginAmount()).isEqualByComparingTo("400");
    }

    // 8-1. 2609_44 / D3: 계산 불가 행은 손익분기가도 null 이고, 그 때문에 요청 전체가 실패하지 않는다.
    //       정상 행은 (5000 + 3000 + 500) / (1 − 0.11) = 9550.56… → 9550 을 함께 내려보낸다.
    @Test
    void candidates_uncalculableRow_breakEvenNull() {
        ProductListing good = cell(CELL_ID);
        ProductListing broken = cell(200L);
        MasterProductOption mo = masterOption(10L);
        givenCandidates(policy("1000", null), List.of(good, broken),
                List.of(option(50L, good, mo, "10000", "10000"), option(51L, broken, mo, "10000", "10000")));
        givenPricingConfig(good, mo);
        given(masterChannelConfigService.resolvePlatformCategory(broken))
                .willThrow(new IllegalArgumentException("수수료 미설정 — 카테고리 시드 필요"));

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).hasSize(2);
        Row brokenRow = response.rows().stream().filter(r -> r.optionId().equals(51L)).findFirst().orElseThrow();
        assertThat(brokenRow.excluded()).isEqualTo(Exclusion.UNCALCULABLE);
        assertThat(brokenRow.breakEvenPrice()).isNull();

        Row goodRow = response.rows().stream().filter(r -> r.optionId().equals(50L)).findFirst().orElseThrow();
        assertThat(goodRow.breakEvenPrice()).isEqualByComparingTo("9550");
    }

    // 9. 🔴 D16: 같은 셀의 옵션이 3개여도 카테고리 매핑·마진 프리셋 조회는 셀당 1회다.
    //    이 테스트가 없으면 N+1 이 조용히 되돌아온다.
    @Test
    void testCandidatesResolvesBasisOncePerCell() {
        ProductListing cell = cell(CELL_ID);
        MasterProductOption mo1 = masterOption(10L);
        MasterProductOption mo2 = masterOption(11L);
        MasterProductOption mo3 = masterOption(12L);
        givenCandidates(policy("1000", null), List.of(cell), Arrays.asList(
                option(50L, cell, mo1, "10000", "10000"),
                option(51L, cell, mo2, "10000", "10000"),
                option(52L, cell, mo3, "10000", "10000")));
        givenPricingConfig(cell, mo1, mo2, mo3);

        RepricingCandidatesResponse response = service.candidates(null, null, RepricingService.Scope.ALL);

        assertThat(response.rows()).hasSize(3);
        verify(masterChannelConfigService, times(1)).resolvePlatformCategory(cell);
        verify(marginPolicyRepository, times(1))
                .findBySellerIdAndPlatform(eq(SELLER_ID), eq(Platform.COUPANG));
        // 배송·박스는 마스터 옵션마다 다를 수 있어 (셀, 마스터옵션)당 1회가 최선이다 — 옵션 3개 = 3회.
        verify(masterChannelConfigService, times(3)).resolveDelivery(eq(cell), any());
        // 셀·옵션·BOM 로드는 각 1쿼리.
        verify(productListingOptionRepository, times(1)).findWithConfigByIdIn(any());
        verify(cellBomResolver, times(1)).forOptions(anyCollection());
    }
}
