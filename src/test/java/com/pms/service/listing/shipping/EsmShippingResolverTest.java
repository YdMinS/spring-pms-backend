package com.pms.service.listing.shipping;

import com.pms.service.CustomerShippingFee;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_26 / D36 · D60 · D66: layer resolution, missing labels, the buyer fee the 3.3% applies to. */
class EsmShippingResolverTest {

    private static final Map<String, String> CHANNEL = Map.of(
            EsmShippingKeys.PLACE_NO, "1", EsmShippingKeys.BUNDLE_POLICY_NO, "10",
            EsmShippingKeys.BUNDLE_FEE_TYPE, "2", EsmShippingKeys.BUNDLE_FEE, "3000",
            EsmShippingKeys.BUNDLE_PREPAY, "true", EsmShippingKeys.DISPATCH_POLICY_NO, "910",
            EsmShippingKeys.RETURN_ADDR_NO, "440753", EsmShippingKeys.COMPANY_NO, "10013",
            EsmShippingKeys.RETURN_FEE, "3000");

    @Test
    void listingWithBothPlaceAndBundle_takesTheWholeGroup_otherKeysPerKey() {
        Map<String, String> listing = Map.of(
                EsmShippingKeys.PLACE_NO, "2", EsmShippingKeys.BUNDLE_POLICY_NO, "20",
                EsmShippingKeys.BUNDLE_FEE_TYPE, "1", EsmShippingKeys.BUNDLE_FEE, "0",
                EsmShippingKeys.BUNDLE_PREPAY, "true", EsmShippingKeys.COMPANY_NO, "10001");

        Map<String, String> resolved = EsmShippingResolver.build(listing, CHANNEL);

        assertThat(resolved.get(EsmShippingKeys.PLACE_NO)).isEqualTo("2");
        assertThat(resolved.get(EsmShippingKeys.BUNDLE_FEE_TYPE)).isEqualTo("1");
        assertThat(resolved.get(EsmShippingKeys.COMPANY_NO)).isEqualTo("10001");
        assertThat(resolved.get(EsmShippingKeys.DISPATCH_POLICY_NO)).isEqualTo("910");
    }

    @Test
    void listingWithoutTheGroup_readsTheChannelGroup() {
        Map<String, String> resolved = EsmShippingResolver.build(Map.of(EsmShippingKeys.RETURN_FEE, "5000"), CHANNEL);

        assertThat(resolved.get(EsmShippingKeys.PLACE_NO)).isEqualTo("1");
        assertThat(resolved.get(EsmShippingKeys.BUNDLE_FEE)).isEqualTo("3000");
        assertThat(resolved.get(EsmShippingKeys.RETURN_FEE)).isEqualTo("5000");
    }

    @Test
    void missingLabels_listsEmptyInputKeysInScreenOrder() {
        assertThat(EsmShippingResolver.missingLabels(Map.of(EsmShippingKeys.PLACE_NO, "1",
                EsmShippingKeys.COMPANY_NO, "10013")))
                .isEqualTo(List.of("묶음배송비", "발송정책", "반품·교환지", "반품 배송비"));
    }

    @Test
    void customerFee_paidPrepaid_isTheFee_conditionalCarriesTheThreshold_codOnlyIsNone() {
        CustomerShippingFee paid = EsmShippingResolver.customerFee(CHANNEL);
        assertThat(paid.charge()).isEqualByComparingTo("3000");
        assertThat(paid.freeShipOver()).isNull();

        CustomerShippingFee conditional = EsmShippingResolver.customerFee(Map.of(
                EsmShippingKeys.BUNDLE_FEE_TYPE, "3", EsmShippingKeys.BUNDLE_FEE, "2500",
                EsmShippingKeys.BUNDLE_FREE_OVER, "20000", EsmShippingKeys.BUNDLE_PREPAY, "true"));
        assertThat(conditional.costAt(new BigDecimal("19990"))).isEqualByComparingTo("82.5");
        assertThat(conditional.costAt(new BigDecimal("20000"))).isEqualByComparingTo("0");

        assertThat(EsmShippingResolver.customerFee(Map.of(
                EsmShippingKeys.BUNDLE_FEE_TYPE, "2", EsmShippingKeys.BUNDLE_FEE, "3000",
                EsmShippingKeys.BUNDLE_PREPAY, "false"))).isEqualTo(CustomerShippingFee.NONE);
        assertThat(EsmShippingResolver.customerFee(Map.of(
                EsmShippingKeys.BUNDLE_FEE_TYPE, "1", EsmShippingKeys.BUNDLE_FEE, "0",
                EsmShippingKeys.BUNDLE_PREPAY, "true"))).isEqualTo(CustomerShippingFee.NONE);
    }
}
