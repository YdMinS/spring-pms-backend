package com.pms.service.listing.shipping;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEATURE_2610_13 / D106: quantity tiers must chain (1003 example 1^10^100 · 9^99 · 5000^1000^0). */
class ElevenstShippingKeysTest {

    @Test
    void validateTiers_officialExample_passes() {
        assertThatCode(() -> ElevenstShippingKeys.validateTiers(Map.of(
                ElevenstShippingKeys.DLV_CNT1, "1^10^100",
                ElevenstShippingKeys.DLV_CNT2, "9^99",
                ElevenstShippingKeys.DLV_CST3, "5000^1000^0"))).doesNotThrowAnyException();
    }

    @Test
    void validateTiers_gapBetweenTiers_rejected() {
        assertThatThrownBy(() -> ElevenstShippingKeys.validateTiers(Map.of(
                ElevenstShippingKeys.DLV_CNT1, "1^11",
                ElevenstShippingKeys.DLV_CNT2, "9",
                ElevenstShippingKeys.DLV_CST3, "5000^0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수량별 배송비 구간이 이어지지 않습니다. 첫 구간은 1개부터, 다음 구간은 앞 구간 끝 수량 + 1 부터 시작해야 합니다.");
    }

    @Test
    void validateTiers_firstTierNotOne_rejected() {
        assertThatThrownBy(() -> ElevenstShippingKeys.validateTiers(Map.of(
                ElevenstShippingKeys.DLV_CNT1, "2",
                ElevenstShippingKeys.DLV_CST3, "3000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTiers_elevenTiers_rejected() {
        assertThatThrownBy(() -> ElevenstShippingKeys.validateTiers(Map.of(
                ElevenstShippingKeys.DLV_CNT1, "1^2^3^4^5^6^7^8^9^10^11",
                ElevenstShippingKeys.DLV_CNT2, "1^2^3^4^5^6^7^8^9^10",
                ElevenstShippingKeys.DLV_CST3, "0^0^0^0^0^0^0^0^0^0^0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수량별 배송비는 10구간까지 넣을 수 있습니다.");
    }
}
