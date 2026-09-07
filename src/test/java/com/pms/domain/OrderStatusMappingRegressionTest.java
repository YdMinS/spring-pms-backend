package com.pms.domain;

import com.pms.service.coupang.CoupangOrderStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중립 주문 상태 매핑·판정 회귀 (FEATURE_2609_26 / PLAN D5·D6·D7).
 *
 * <p>6상태 왕복(쿠팡 코드 → OrderStatus → 쿠팡 조회 파라미터)이 이 파일의 요지다 — 매핑이 한쪽만
 * 바뀌면 동기화 범위(OrderSyncScope)와 발송처리 스킵이 조용히 갈라진다.
 */
class OrderStatusMappingRegressionTest {

    @Test
    void 쿠팡6코드가중립상태로매핑된다() {
        assertThat(OrderStatus.fromCoupang("ACCEPT")).contains(OrderStatus.PAID);
        assertThat(OrderStatus.fromCoupang("INSTRUCT")).contains(OrderStatus.PREPARING);
        assertThat(OrderStatus.fromCoupang("DEPARTURE")).contains(OrderStatus.SHIPPED);
        assertThat(OrderStatus.fromCoupang("DELIVERING")).contains(OrderStatus.DELIVERING);
        assertThat(OrderStatus.fromCoupang("FINAL_DELIVERY")).contains(OrderStatus.DELIVERED);
        // 업체 직접배송은 워크플로상 발송 완료 — 추적 가능 여부는 배송 묶음의 속성이다.
        assertThat(OrderStatus.fromCoupang("NONE_TRACKING")).contains(OrderStatus.SHIPPED);
    }

    @Test
    void 쿠팡terminal4코드는매핑후에도전부terminal이다() {
        // 2609_14 동기화 창 축소·2609_07 발송처리 스킵이 이 판정 위에 서 있다.
        // 판정의 소유자는 OrderStatus 하나다 — CoupangOrderStatus 에 isTerminal 을 되살리지 말 것.
        assertThat(Arrays.stream(CoupangOrderStatus.values())
                .filter(coupang -> coupang.toOrderStatus().isTerminal()))
                .hasSize(4)
                .allSatisfy(coupang -> assertThat(OrderStatus.fromCoupang(coupang.name()))
                        .get()
                        .matches(OrderStatus::isTerminal, coupang.name() + " must stay terminal"));
    }

    @Test
    void 중립상태에서쿠팡조회파라미터로되돌아온다() {
        // 왕복(쿠팡 코드 → 중립 → 쿠팡 조회 파라미터). SHIPPED 만 1:N 이다(DEPARTURE + NONE_TRACKING).
        for (CoupangOrderStatus coupang : CoupangOrderStatus.values()) {
            assertThat(CoupangOrderStatus.forOrderStatus(coupang.toOrderStatus()))
                    .as("%s must survive the round trip", coupang)
                    .contains(coupang);
        }
        assertThat(CoupangOrderStatus.forOrderStatus(OrderStatus.SHIPPED))
                .containsExactly(CoupangOrderStatus.DEPARTURE, CoupangOrderStatus.NONE_TRACKING);
        assertThat(CoupangOrderStatus.forOrderStatus(OrderStatus.PAID))
                .containsExactly(CoupangOrderStatus.ACCEPT);
        // 파생값이라 쿠팡에는 대응 코드가 없다.
        assertThat(CoupangOrderStatus.forOrderStatus(OrderStatus.CANCELLED)).isEmpty();
    }

    @Test
    void 활성2코드는terminal이아니다() {
        assertThat(OrderStatus.fromCoupang("ACCEPT")).get().matches(s -> !s.isTerminal());
        assertThat(OrderStatus.fromCoupang("INSTRUCT")).get().matches(s -> !s.isTerminal());
    }

    @Test
    void 모르는값과빈값은empty다() {
        // 🔴 임의 기본값을 주면 "모르는 상태 = 전송" 이라는 오발송 방향으로 실패한다(D7).
        assertThat(OrderStatus.fromCoupang("SOMETHING_NEW")).isEmpty();
        assertThat(OrderStatus.fromCoupang("")).isEmpty();
        assertThat(OrderStatus.fromCoupang(null)).isEqualTo(Optional.empty());
    }

    @Test
    void 전량취소는저장값이아니라파생값이다() {
        OrderLine line = OrderLine.builder()
                .status(OrderStatus.PREPARING).orderQty(2).cancelQty(1).holdQty(1).build();
        assertThat(line.isFullyCancelled()).isTrue();
        assertThat(line.effectiveStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(line.getStatus()).isEqualTo(OrderStatus.PREPARING);   // 저장값은 그대로
        assertThat(line.purchasableQty()).isZero();
    }
}
