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
import com.pms.service.listing.shipping.LotteonShippingKeys;
import com.pms.service.listing.shipping.LotteonShippingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** FEATURE_2610_24 / D66: a LotteON cell pays its category commission (VAT included) on the policy's fee too. */
@ExtendWith(MockitoExtension.class)
class LotteonPriceCalculatorTest {

    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private LotteonShippingResolver lotteonShippingResolver;

    private PriceCalculator calculator;

    private final ProductListing cell = ProductListing.builder().id(3L).platform(Platform.LOTTEON)
            .seller(Seller.builder().id(7L).sellerName("판매자").build()).build();
    private final MasterProductOption masterOption = MasterProductOption.builder().id(5L).name("기본").build();

    @BeforeEach
    void setUp() {
        calculator = new PriceCalculator(marginPolicyRepository, masterChannelConfigService, new BigDecimal("0.1"),
                null, null, lotteonShippingResolver);
        given(masterChannelConfigService.resolvePlatformCategory(cell)).willReturn(PlatformCategory.builder()
                .id(4L).platform(Platform.LOTTEON).code("BC1").commissionRate(new BigDecimal("0.10")).build());
        given(masterChannelConfigService.resolveDelivery(cell, masterOption))
                .willReturn(CarrierRate.builder().cost(new BigDecimal("2500")).build());
        given(masterChannelConfigService.resolvePackage(cell, masterOption))
                .willReturn(Package.builder().cost(new BigDecimal("500")).build());
        given(marginPolicyRepository.findBySellerIdAndPlatform(7L, Platform.LOTTEON))
                .willReturn(Optional.of(MarginPolicy.builder().marginRate(new BigDecimal("0.15")).build()));
    }

    @Test
    void paidPolicy_addsFeeTimesTheEffectiveCommission() {
        given(lotteonShippingResolver.resolve(cell)).willReturn(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "A", LotteonShippingKeys.DV_CST, "3000"));

        // (5000 + 2500 + 500 + 3000 × 0.10 × 1.1 = 8330) / (1 − 0.11 − 0.15 = 0.74) = 11256.76 → 11260
        assertThat(calculator.calculatePrice(cell, masterOption, new BigDecimal("5000"))).isEqualByComparingTo("11260");
    }

    @Test
    void conditionalPolicy_atOrAboveTheThreshold_addsNothing() {
        given(lotteonShippingResolver.resolve(cell)).willReturn(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "C", LotteonShippingKeys.DV_CST, "3000",
                LotteonShippingKeys.CNDL_FREE_STD_AMT, "10000"));

        // 8000 / 0.74 = 10810.81 → 10810 ≥ 10000 → free
        assertThat(calculator.calculatePrice(cell, masterOption, new BigDecimal("5000"))).isEqualByComparingTo("10810");
    }

    @Test
    void freePolicy_addsNothing() {
        given(lotteonShippingResolver.resolve(cell)).willReturn(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "B", LotteonShippingKeys.DV_CST, "0"));

        assertThat(calculator.calculatePrice(cell, masterOption, new BigDecimal("5000"))).isEqualByComparingTo("10810");
    }
}
