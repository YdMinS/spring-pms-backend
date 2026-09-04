package com.pms.service.coupang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderSyncScope — 범위별 조회 상태 목록(FEATURE_2609_16 D1·D2).
 *
 * 순수 단위테스트다(enum 의 상태 목록만 본다) — Mockito·Spring 컨텍스트 불필요.
 */
class OrderSyncScopeTest {

    @Test
    void FULL은_전상태() {
        assertThat(OrderSyncScope.FULL.statuses())
                .containsExactly(CoupangOrderStatus.values());
    }

    @Test
    void ACTIVE는_결제완료와_상품준비중만() {
        assertThat(OrderSyncScope.ACTIVE.statuses())
                .containsExactly(CoupangOrderStatus.ACCEPT, CoupangOrderStatus.INSTRUCT);
    }

    @Test
    void ACTIVE에_종결상태가_하나도_없다() {
        // D2: ACTIVE 는 isTerminal() 의 여집합이다. 상태 목록을 복제하면 이 테스트가 깨진다.
        assertThat(OrderSyncScope.ACTIVE.statuses()).noneMatch(CoupangOrderStatus::isTerminal);
    }
}
