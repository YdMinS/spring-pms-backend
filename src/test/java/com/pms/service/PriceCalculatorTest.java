package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.MarginPolicy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Margin reverse-calc engine (FEATURE_2608_06 / 3b-2): the price formula + rounding, the missing-margin
 * 400, and the denominator ≤ 0 (commission + margin ≥ 100%) 400. The commission fallback itself is
 * {@code CommissionRateService.findRate} reuse — not re-tested here.
 */
@ExtendWith(MockitoExtension.class)
class PriceCalculatorTest {

    @Mock private CommissionRateService commissionRateService;
    @Mock private MarginPolicyRepository marginPolicyRepository;
    @InjectMocks private PriceCalculator priceCalculator;

    private ProductListing cell() {
        return ProductListing.builder()
                .id(1L)
                .platform("COUPANG")
                .seller(Seller.builder().id(7L).sellerName("판매자").build())
                .delivery(CarrierRate.builder().cost(new BigDecimal("2500")).build())
                .package_(Package.builder().cost(new BigDecimal("500")).build())
                .build();
    }

    @Test
    void calculatePrice_appliesFormulaAndRoundsToTenWon() {
        // cost 5000 + delivery 2500 + box 500 = 8000; 1 − 0.10 − 0.15 = 0.75; 8000/0.75 = 10666.67 → 10670
        given(commissionRateService.findRate("COUPANG", null)).willReturn(new BigDecimal("0.10"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));

        BigDecimal price = priceCalculator.calculatePrice(cell(), new BigDecimal("5000"));

        assertThat(price).isEqualByComparingTo("10670");
    }

    @Test
    void calculatePrice_missingMarginPreset_throws400() {
        given(commissionRateService.findRate("COUPANG", null)).willReturn(new BigDecimal("0.10"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell(), new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마진 프리셋 없음");
    }

    @Test
    void calculatePrice_denominatorNotPositive_throws400() {
        // commission 0.60 + margin 0.50 = 1.10 → 1 − 1.10 = −0.10 ≤ 0
        given(commissionRateService.findRate("COUPANG", null)).willReturn(new BigDecimal("0.60"));
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, "COUPANG"))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.50")).build()));

        assertThatThrownBy(() -> priceCalculator.calculatePrice(cell(), new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수수료+마진이 100% 이상");
    }
}
