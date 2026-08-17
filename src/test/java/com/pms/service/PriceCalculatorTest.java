package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
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
 * Margin reverse-calc engine (FEATURE_2608_06 / 3b-2, rewired / 13): category/delivery/box now come from the
 * {@link MasterChannelConfigService} (option override ?? master default), so those inputs are mocked here.
 * This test covers the price formula + rounding, the missing-margin 400, the denominator ≤ 0 400, and that a
 * resolver 400 (unset config) propagates. The resolver's own null-check logic is covered by
 * {@code MasterChannelConfigServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PriceCalculatorTest {

    @Mock private CommissionRateService commissionRateService;
    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @InjectMocks private PriceCalculator priceCalculator;

    private final ProductListing cell = ProductListing.builder()
            .id(1L).platform("COUPANG")
            .seller(Seller.builder().id(7L).sellerName("판매자").build())
            .build();
    private final MasterProductOption masterOption = MasterProductOption.builder().id(5L).name("기본").build();
    private final Category category = Category.builder().id(3L).build();

    private void stubConfig(String deliveryCost, String boxCost) {
        given(masterChannelConfigService.resolveCategory(cell)).willReturn(category);
        given(masterChannelConfigService.resolveDelivery(cell, masterOption))
                .willReturn(CarrierRate.builder().cost(new BigDecimal(deliveryCost)).build());
        given(masterChannelConfigService.resolvePackage(cell, masterOption))
                .willReturn(Package.builder().cost(new BigDecimal(boxCost)).build());
    }

    @Test
    void calculatePrice_appliesFormulaAndRoundsToTenWon() {
        // cost 5000 + delivery 2500 + box 500 = 8000; 1 − 0.10 − 0.15 = 0.75; 8000/0.75 = 10666.67 → 10670
        stubConfig("2500", "500");
        given(commissionRateService.findRate("COUPANG", 3L)).willReturn(new BigDecimal("0.10"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));

        BigDecimal price = priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000"));

        assertThat(price).isEqualByComparingTo("10670");
    }

    @Test
    void calculatePrice_missingMarginPreset_throws400() {
        stubConfig("2500", "500");
        given(commissionRateService.findRate("COUPANG", 3L)).willReturn(new BigDecimal("0.10"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마진 프리셋 없음");
    }

    @Test
    void calculatePrice_denominatorNotPositive_throws400() {
        // commission 0.60 + margin 0.50 = 1.10 → 1 − 1.10 = −0.10 ≤ 0
        stubConfig("2500", "500");
        given(commissionRateService.findRate("COUPANG", 3L)).willReturn(new BigDecimal("0.60"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.50")).build()));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수수료+마진이 100% 이상");
    }

    @Test
    void calculatePrice_categoryUnset_resolverThrows400_propagates() {
        given(masterChannelConfigService.resolveCategory(cell))
                .willThrow(new IllegalArgumentException("카테고리 미설정"));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell, masterOption, new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("카테고리 미설정");
    }
}
