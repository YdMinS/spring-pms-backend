package com.pms.service.listing.shipping;

import com.pms.service.CustomerShippingFee;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_24 / D42 · D65 · D66: layers, the missing-field labels and the buyer fee. */
class LotteonShippingResolverTest {

    private static final BigDecimal RATE = new BigDecimal("0.11");

    @Test
    void build_placesFallBackOneByOne_policyGroupComesWholeFromTheLayerThatPickedIt() {
        Map<String, String> channel = Map.of(
                LotteonShippingKeys.OWHP_NO, "C-OUT", LotteonShippingKeys.RTRP_NO, "C-RET",
                LotteonShippingKeys.DV_CST_POL_NO, "C-POL", LotteonShippingKeys.DV_CST_DVS_CD, "C",
                LotteonShippingKeys.DV_CST, "3000", LotteonShippingKeys.CNDL_FREE_STD_AMT, "30000");
        Map<String, String> listing = Map.of(
                LotteonShippingKeys.OWHP_NO, "L-OUT",
                LotteonShippingKeys.DV_CST_POL_NO, "L-POL", LotteonShippingKeys.DV_CST_DVS_CD, "A",
                LotteonShippingKeys.DV_CST, "2500");

        Map<String, String> resolved = LotteonShippingResolver.build(listing, channel);

        assertThat(resolved).containsEntry(LotteonShippingKeys.OWHP_NO, "L-OUT")
                .containsEntry(LotteonShippingKeys.RTRP_NO, "C-RET")
                .containsEntry(LotteonShippingKeys.DV_CST_POL_NO, "L-POL")
                .containsEntry(LotteonShippingKeys.DV_CST, "2500")
                .doesNotContainKey(LotteonShippingKeys.CNDL_FREE_STD_AMT);
    }

    @Test
    void missingLabels_inScreenOrder() {
        assertThat(LotteonShippingResolver.missingLabels(Map.of(LotteonShippingKeys.RTRP_NO, "R")))
                .containsExactly("출고지", "배송비 정책");
    }

    @Test
    void customerFee_paidConditionalFree() {
        CustomerShippingFee paid = LotteonShippingResolver.customerFee(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "A", LotteonShippingKeys.DV_CST, "3000"), RATE);
        assertThat(paid.costAt(new BigDecimal("50000"))).isEqualByComparingTo("330");

        CustomerShippingFee conditional = LotteonShippingResolver.customerFee(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "C", LotteonShippingKeys.DV_CST, "3000",
                LotteonShippingKeys.CNDL_FREE_STD_AMT, "30000"), RATE);
        assertThat(conditional.costAt(new BigDecimal("29990"))).isEqualByComparingTo("330");
        assertThat(conditional.costAt(new BigDecimal("30000"))).isEqualByComparingTo("0");

        assertThat(LotteonShippingResolver.customerFee(Map.of(
                LotteonShippingKeys.DV_CST_DVS_CD, "B", LotteonShippingKeys.DV_CST, "0"), RATE))
                .isEqualTo(CustomerShippingFee.NONE);
        assertThat(LotteonShippingResolver.customerFee(Map.of(), RATE)).isEqualTo(CustomerShippingFee.NONE);
    }
}
