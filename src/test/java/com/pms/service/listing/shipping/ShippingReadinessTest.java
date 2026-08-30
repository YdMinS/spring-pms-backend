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

    // ── 96 ⑧: 무료배송(FREE) ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("무료배송: deliveryCharge 가 null 이어도 누락이 아니다(FREE→0)")
    void check_freeShipping_nullDeliveryCharge_isReady() {
        // Given 무료배송인데 배송비 칸이 비어 있음 (사용자가 채울 방법이 없는 정상 상태)
        ResolvedShippingConfig cfg = new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENTIAL", "CJGLS", "FREE", null, null,
                "Y", "NOT_UNION_DELIVERY", null);

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then 예전엔 deliveryCharge 가 영구 누락으로 잡혀 [마켓 등록] 이 계속 비활성이었다
        assertThat(readiness.missing()).isEmpty();
        assertThat(readiness.ready()).isTrue();
        assertThat(ShippingReadiness.effectiveDeliveryCharge(cfg)).isEqualByComparingTo("0");
        assertThat(ShippingReadiness.effectiveFreeShipOverAmount(cfg)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("조건부 무료배송: freeShipOverAmount 가 null 이어도 기존대로 ready (새로 필수화하지 않음)")
    void check_conditionalFree_nullFreeShipOverAmount_staysReady() {
        // Given 조건부 무료배송인데 기준 금액이 비어 있음
        ResolvedShippingConfig cfg = new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENTIAL", "CJGLS", "CONDITIONAL_FREE", new BigDecimal("3000"), null,
                "Y", "NOT_UNION_DELIVERY", null);

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then ⑧ 수정이 freeShipOverAmount 를 필수로 만들지 않았음을 고정(만들면 이 설정이 새로 누락 판정)
        assertThat(readiness.missing()).isEmpty();
        assertThat(readiness.ready()).isTrue();
        // 판정과 달리 payload 는 CONDITIONAL_FREE 를 건드리지 않는다 (null 그대로)
        assertThat(ShippingReadiness.effectiveDeliveryCharge(cfg)).isEqualByComparingTo("3000");
        assertThat(ShippingReadiness.effectiveFreeShipOverAmount(cfg)).isNull();
    }

    @Test
    @DisplayName("무료배송 아님: deliveryCharge 가 null 이면 여전히 누락")
    void check_paidShipping_nullDeliveryCharge_isMissing() {
        // Given 유료배송인데 배송비가 비어 있음
        ResolvedShippingConfig cfg = new ResolvedShippingConfig(
                "OUT-1",
                "RET-1", "홍길동", "010-1111-2222", "12345", "서울시 강남구", "101동 202호",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENTIAL", "CJGLS", "NOT_FREE", null, null,
                "Y", "NOT_UNION_DELIVERY", null);

        // When
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);

        // Then FREE 완화가 다른 배송비 타입까지 번지지 않았음
        assertThat(readiness.missing()).contains("deliveryCharge");
        assertThat(readiness.ready()).isFalse();
    }
}
