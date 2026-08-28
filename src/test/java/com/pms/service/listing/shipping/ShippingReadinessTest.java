package com.pms.service.listing.shipping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the readiness rules extracted from {@code CoupangListingAdapter.requireShippingConfig}
 * (FEATURE_2608_06 / 77). No Mockito — {@link ShippingReadiness} takes a plain record.
 */
class ShippingReadinessTest {

    /** Every register-required field present, no forbidden combination. */
    private static ResolvedShippingConfig complete() {
        return new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENTIAL", "CJGLS", "FREE", BigDecimal.ZERO, new BigDecimal("30000"),
                "Y", "NOT_UNION_DELIVERY", "안내문구");
    }

    @Test
    @DisplayName("완비: 전 필드가 채워지면 missing 없음·충돌 없음·ready")
    void check_completeConfig_isReady() {
        // Given a fully populated resolved config
        ResolvedShippingConfig cfg = complete();

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then
        assertThat(readiness.missing()).isEmpty();
        assertThat(readiness.unionChargeConflict()).isFalse();
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    @DisplayName("누락: deliveryMethod 가 null 이면 missing 에 잡히고 ready=false")
    void check_missingDeliveryMethod_isNotReady() {
        // Given deliveryMethod cleared
        ResolvedShippingConfig cfg = new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                null, "CJGLS", "FREE", BigDecimal.ZERO, new BigDecimal("30000"),
                "Y", "NOT_UNION_DELIVERY", null);

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then
        assertThat(readiness.missing()).contains("deliveryMethod");
        assertThat(readiness.ready()).isFalse();
    }

    @Test
    @DisplayName("충돌: 묶음배송(UNION_DELIVERY) + 착불(CHARGE_RECEIVED) 은 필드가 다 차 있어도 ready=false")
    void check_unionDeliveryWithChargeReceived_conflicts() {
        // Given otherwise complete config with the forbidden combination
        ResolvedShippingConfig cfg = new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENTIAL", "CJGLS", "CHARGE_RECEIVED", BigDecimal.ZERO, null,
                "Y", "UNION_DELIVERY", null);

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then
        assertThat(readiness.missing()).isEmpty();
        assertThat(readiness.unionChargeConflict()).isTrue();
        assertThat(readiness.ready()).isFalse();
    }
}
