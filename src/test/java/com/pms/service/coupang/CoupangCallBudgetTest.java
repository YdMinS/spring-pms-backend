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
        CoupangProperties properties = new CoupangProperties();
        // 버킷 동작 확인용 고정치다 — 운영 기본값이 바뀌어도 이 테스트들이 흔들리지 않게 둘 다 박는다.
        // 기본값이 얼마여야 하는지는 defaultSettings_ 테스트가 따로 고정한다.
        properties.setCallsPerSecond(4.0);
        properties.setCallBurst(5);                               // rate=4/s, burst=5
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
    void defaultSettings_keepAnyOneSecondWindowWithinCoupangsLimit() {
        // 🔴 어떤 1초 구간에 나갈 수 있는 최대 = 버스트 + 그 사이 보충량. 쿠팡 한도는 업체코드당
        // 초당 5회다. 버스트가 5 이던 시절 prod 한 구간에 7건이 나갔다(2026-09-14).
        // 상한을 4 로 잠근다 — 5(한도와 동일)는 여유가 0 이라 2026-09-16 prod 429 를 막지 못했다.
        CoupangCallBudget defaults = new CoupangCallBudget(new CoupangProperties(), clock, duration -> {
            waits.add(duration);
            clock.advance(duration);
        });

        List<Instant> sentAt = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            defaults.acquire(VENDOR);
            sentAt.add(clock.instant());
        }

        long withinFirstSecond = sentAt.stream().filter(at -> !at.isAfter(T0.plusSeconds(1))).count();
        assertThat(withinFirstSecond).isLessThanOrEqualTo(4);
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
