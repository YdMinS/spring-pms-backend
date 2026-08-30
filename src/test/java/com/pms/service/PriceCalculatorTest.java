package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarginPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 */
@ExtendWith(MockitoExtension.class)
class PriceCalculatorTest {

    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @InjectMocks private PriceCalculator priceCalculator;

    private final ProductListing cell = ProductListing.builder()
            .id(1L).platform("COUPANG")
            .seller(Seller.builder().id(7L).sellerName("판매자").build())
            .build();
    private final MasterProductOption masterOption = MasterProductOption.builder().id(5L).name("기본").build();

    private PlatformCategory platformCategory(String commission) {
        return PlatformCategory.builder().id(3L).platform("COUPANG").code("cat-1")
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
        // cost 5000 + delivery 2500 + box 500 = 8000; 1 − 0.10 − 0.15 = 0.75; 8000/0.75 = 10666.67 → 10670
        stubConfig("0.10", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));

        BigDecimal price = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"));

        assertThat(price).isEqualByComparingTo("10670");
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
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마진 프리셋 없음");
    }

    @Test
    void calculatePrice_denominatorNotPositive_throws400() {
        // commission 0.60 + margin 0.50 = 1.10 → 1 − 1.10 = −0.10 ≤ 0
        stubConfig("0.60", "2500", "500");
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.50")).build()));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수수료+마진이 100% 이상");
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
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder()
                        .marginRate(new BigDecimal(marginRate))
                        .displayDiscountRate(discountRate == null ? null : new BigDecimal(discountRate))
                        .build()));
    }

    @Test
    void calculatePrices_discountRateZero_originalPriceEqualsSalePrice() {
        stubMargin("0.15", "0.0");   // salePrice 8000/0.75 = 10670

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.salePrice()).isEqualByComparingTo("10670");
        assertThat(result.originalPrice()).isEqualTo(result.salePrice());   // scale-equal, no discount shown
    }

    @Test
    void calculatePrices_discountRate20Percent_originalPriceRoundedToTenWon() {
        stubMargin("0.15", "0.2");   // originalPrice = round10(10670 / 0.8) = round10(13337.5) = 13340

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo("13340");
    }

    @Test
    void calculatePrices_discountRateNull_treatedAsZero() {
        stubMargin("0.15", null);

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo(result.salePrice());
    }

    @Test
    void calculatePrices_discountRateAboveCap_clampedToHalf() {
        stubMargin("0.15", "0.6");   // clamp → 0.5; originalPrice = round10(10670 / 0.5) = 21340

        PriceCalculator.PriceResult result = priceCalculator.calculatePrices(cell, masterOption, new BigDecimal("5000"));

        assertThat(result.originalPrice()).isEqualByComparingTo("21340");
    }
}
