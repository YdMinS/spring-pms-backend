package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업체코드별 토큰버킷 속도 제한 단위 테스트 — 실제로 잠들지 않는다(Waiter 스텁 + MutableClock).
 */
class CoupangCallBudgetTest {

    private static final Instant T0 = Instant.parse("2026-09-14T00:00:00Z");
    private static final String VENDOR = "A001";
    private static final String OTHER_VENDOR = "A002";

    private MutableClock clock;
    private List<Duration> waits;
    private CoupangCallBudget budget;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        waits = new ArrayList<>();
        CoupangProperties properties = new CoupangProperties();   // rate=4/s, burst=5
        // ⚠️ 스텁이 시계를 함께 밀어야 acquire 루프가 무한히 돌지 않는다.
        budget = new CoupangCallBudget(properties, clock, duration -> {
            waits.add(duration);
            clock.advance(duration);
        });
    }

    @Test
    void acquire_burstGoesOutWithoutWaiting() {
        for (int i = 0; i < 5; i++) {
            budget.acquire(VENDOR);
        }

        assertThat(waits).isEmpty();
    }

    @Test
    void acquire_waitsWhenBurstDrained() {
        for (int i = 0; i < 5; i++) {
            budget.acquire(VENDOR);
        }

        budget.acquire(VENDOR);

        assertThat(waits).hasSize(1);
        assertThat(waits.get(0).toMillis()).isBetween(230L, 270L);   // 1/rate = 250ms
    }

    @Test
    void acquire_refillsOverTime() {
        for (int i = 0; i < 5; i++) {
            budget.acquire(VENDOR);
        }
        clock.advance(Duration.ofSeconds(1));   // 4 tokens refilled

        for (int i = 0; i < 4; i++) {
            budget.acquire(VENDOR);
        }

        assertThat(waits).isEmpty();
    }

    @Test
    void acquire_separateVendorsHaveSeparateBuckets() {
        for (int i = 0; i < 5; i++) {
            budget.acquire(VENDOR);
        }

        budget.acquire(OTHER_VENDOR);

        assertThat(waits).isEmpty();
    }
}
