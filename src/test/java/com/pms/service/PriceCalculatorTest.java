package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarginPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Margin reverse-calc engine (FEATURE_2608_06 / 3b-2, rewired / 13 and / 52): commission now comes from the
 * mapped {@link PlatformCategory} owned by the {@link MasterChannelConfigService} (52), and delivery/box from
 * the same resolver (option override ?? master default), so those inputs are mocked here. This test covers the
 * price formula + rounding, the missing-commission 400, the missing-margin 400, the denominator ≤ 0 400, and
 * that a resolver 400 (unset config) propagates. The resolver's own null-check logic is covered by
 * {@code MasterChannelConfigServiceTest}.
 *
 * <p>🔴 FEATURE_2609_30 / 07 (PLAN D19): the formula now subtracts the commission <b>and the VAT charged on that
 * commission</b>, so every expected price below moved up. {@link #zeroVatRateMatchesLegacyFormula} is the
 * regression guarantee — with {@code feeVatRate = 0} the engine must still produce the pre-D19 numbers, which is
 * what separates "the rate changed" from "the formula broke".</p>
 */
@ExtendWith(MockitoExtension.class)
class PriceCalculatorTest {

    /** Commission VAT rate under test = the production default {@code oclyx.pricing.fee-vat-rate: 0.1}. */
    private static final BigDecimal VAT = new BigDecimal("0.1");

    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    private PriceCalculator priceCalculator;
    /** Same mocks, {@code feeVatRate = 0} — reproduces the pre-D19 engine for the regression comparisons. */
    private PriceCalculator legacyCalculator;

    @BeforeEach
    void setUp() {
        priceCalculator = new PriceCalculator(marginPolicyRepository, masterChannelConfigService, VAT);
        legacyCalculator = new PriceCalculator(marginPolicyRepository, masterChannelConfigService, BigDecimal.ZERO);
    }

    private final ProductListing cell = ProductListing.builder()
            .id(1L).platform(Platform.COUPANG)
            .seller(Seller.builder().id(7L).sellerName("판매자").build())
            .build();
    private final MasterProductOption masterOption = MasterProductOption.builder().id(5L).name("기본").build();

    private PlatformCategory platformCategory(String commission) {
        return PlatformCategory.builder().id(3L).platform(Platform.COUPANG).code("cat-1")
                .commissionRate(commission == null ? null : new BigDecimal(commission)).build();
    }

    private void stubConfig(String commission, String deliveryCost, String boxCost) {
        given(masterChannelConfigService.resolvePlatformCategory(cell)).willReturn(platformCategory(commission));
        given(masterChannelConfigService.resolveDelivery(cell, masterOption))
                .willReturn(CarrierRate.builder().cost(new BigDecimal(deliveryCost)).build());
        given(masterChannelConfigService.resolvePackage(cell, masterOption))
                .willReturn(Package.builder().cost(new BigDecimal(boxCost)).build());
    }

    @Test
    void calculatePrice_appliesFormulaAndRoundsToTenWon() {
        // PLAN 2609_30 D19: the commission now carries its own VAT, so the denominator shrank (was 0.75 → 10670).
        // cost 5000 + delivery 2500 + box 500 = 8000; 1 − 0.10 × 1.1 − 0.15 = 0.74; 8000/0.74 = 10810.81 → 10810
        stubConfig("0.10", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));

        BigDecimal price = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"));

        assertThat(price).isEqualByComparingTo("10810");
    }

    @Test
    void calculatePrice_commissionUnsetOnPlatformCategory_throws400() {
        // Mapped PlatformCategory has null commission = seeding gap, no runtime fallback.
        given(masterChannelConfigService.resolvePlatformCategory(cell)).willReturn(platformCategory(null));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수수료 미설정 — 카테고리 시드 필요");
    }

    @Test
    void calculatePrice_missingMarginPreset_throws400() {
        stubConfig("0.10", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마진 프리셋 없음");
    }

    @Test
    void calculatePrice_denominatorNotPositive_throws400() {
        // commission 0.60 × 1.1 = 0.66 + margin 0.50 = 1.16 → 1 − 1.16 = −0.16 ≤ 0
        stubConfig("0.60", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.50")).build()));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수수료+마진이 100% 이상")
                // D19: the effective (VAT-included) rate is in the message so the cause is readable.
                .hasMessageContaining("실효 수수료율 0.660");
    }

    @Test
    void calculatePrice_categoryUnset_resolverThrows400_propagates() {
        given(masterChannelConfigService.resolvePlatformCategory(cell))
                .willThrow(new IllegalArgumentException("표준 카테고리 미설정"));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("표준 카테고리 미설정");
    }

    // --- 73: originalPrice reverse-calc (displayDiscountRate) ---

    private void stubMargin(String marginRate, String discountRate) {
        stubConfig("0.10", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder()
                        .marginRate(new BigDecimal(marginRate))
                        .displayDiscountRate(discountRate == null ? null : new BigDecimal(discountRate))
                        .build()));
    }

    @Test
    void calculatePrices_discountRateZero_originalPriceEqualsSalePrice() {
        stubMargin("0.15", "0.0");   // salePrice 8000/0.74 = 10810 (D19: was 8000/0.75 = 10670)

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.salePrice()).isEqualByComparingTo("10810");
        assertThat(result.originalPrice()).isEqualTo(result.salePrice());   // scale-equal, no discount shown
    }

    @Test
    void calculatePrices_discountRate20Percent_originalPriceRoundedToTenWon() {
        // D19: salePrice moved 10670 → 10810, so the display price follows it.
        stubMargin("0.15", "0.2");   // originalPrice = round10(10810 / 0.8) = round10(13512.5) = 13510

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo("13510");
    }

    @Test
    void calculatePrices_discountRateNull_treatedAsZero() {
        stubMargin("0.15", null);

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo(result.salePrice());
    }

    @Test
    void calculatePrices_discountRateAboveCap_clampedToHalf() {
        stubMargin("0.15", "0.6");   // clamp → 0.5; originalPrice = round10(10810 / 0.5) = 21620 (D19)

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo("21620");
    }

    // --- FEATURE_2609_30 / 07: commission VAT in the reverse-calc (PLAN D19) ---

    /**
     * 🔴 Regression guarantee. {@code feeVatRate = 0} must reproduce the pre-D19 numbers exactly — sale price AND
     * display price. If this breaks, the formula's structure changed, not just the rate.
     */
    @Test
    void zeroVatRateMatchesLegacyFormula() {
        stubMargin("0.15", "0.2");   // legacy: 8000/0.75 = 10666.67 → 10670; original = round10(10670/0.8) = 13340

        PriceCalculator.PriceResult legacy = legacyCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(legacy.salePrice()).isEqualByComparingTo("10670");
        assertThat(legacy.originalPrice()).isEqualByComparingTo("13340");
    }

    /**
     * Coupang settles commission + 10% VAT on that commission, so a 10.6% category effectively costs 11.66%.
     * Hand calc: (5000 + 2500 + 500) / (1 − 0.106 × 1.1 − 0.10) = 8000 / 0.7834 = 10211.89… → 10210.
     * Without the VAT it would be 8000 / 0.794 = 10075.56… → 10080, i.e. the margin silently fell short.
     */
    @Test
    void vatRaisesPriceByFeeVat() {
        stubConfig("0.106", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.10")).build()));

        BigDecimal withVat = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"));
        BigDecimal legacy = legacyCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"));

        assertThat(withVat).isEqualByComparingTo("10210");
        assertThat(legacy).isEqualByComparingTo("10080");
    }

    /** The VAT is charged on the commission, so a higher-commission category is pushed up more (absolute won). */
    @Test
    void higherCommissionCategoryMovesMore() {
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));

        stubConfig("0.10", "2500", "500");
        BigDecimal lowMove = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"))
                .subtract(legacyCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")));

        stubConfig("0.20", "2500", "500");   // re-stub the same resolver call with the pricier category
        BigDecimal highMove = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"))
                .subtract(legacyCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")));

        assertThat(lowMove).isEqualByComparingTo("140");    // 10810 − 10670
        assertThat(highMove).isEqualByComparingTo("390");   // 12700 − 12310
        assertThat(highMove).isGreaterThan(lowMove);
    }

    /**
     * The guard is unchanged, but the VAT shrinks the denominator: commission 46% + margin 50% used to pass
     * (1 − 0.96 = 0.04) and now trips it (1 − 0.506 − 0.50 = −0.006 ≤ 0).
     */
    @Test
    void denominatorGuardStillThrows() {
        stubConfig("0.46", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.50")).build()));

        assertThat(legacyCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isNotNull();   // same inputs are fine without the VAT
        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수수료+마진이 100% 이상")
                .hasMessageContaining("실효 수수료율 0.506");
    }

    /** The standalone display-price helper (2609_19 / D7) derives from whatever sale price it is handed. */
    @Test
    void displayOriginalPriceFollowsNewSalePrice() {
        stubMargin("0.15", "0.2");

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.salePrice()).isEqualByComparingTo("10810");
        assertThat(priceCalculator.displayOriginalPrice(cell, result.salePrice()))
                .isEqualByComparingTo(result.originalPrice())
                .isEqualByComparingTo("13510");
    }
}
