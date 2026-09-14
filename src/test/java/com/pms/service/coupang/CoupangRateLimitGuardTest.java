package com.pms.service.coupang;

import com.pms.exception.CoupangRateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 429 쿨다운 서킷 단위 테스트 — Clock 을 밀어 검증한다(Spring 컨텍스트 없음).
 */
class CoupangRateLimitGuardTest {

    private static final Instant T0 = Instant.parse("2026-09-02T00:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String VENDOR = "A001";
    private static final String OTHER_VENDOR = "A002";

    @Test
    void check_passes_whenNeverTripped() {
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(Clock.fixed(T0, UTC));

        assertThatCode(() -> guard.check(VENDOR)).doesNotThrowAnyException();
    }

    @Test
    void check_throws_duringCooldown() {
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(Clock.fixed(T0, UTC));

        guard.trip(VENDOR);

        assertThatThrownBy(() -> guard.check(VENDOR)).isInstanceOf(CoupangRateLimitedException.class);
    }

    @Test
    void check_passes_afterCooldownElapsed() {
        MutableClock clock = new MutableClock(T0);
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(clock);

        guard.trip(VENDOR);
        clock.advance(Duration.ofMinutes(11));

        assertThatCode(() -> guard.check(VENDOR)).doesNotThrowAnyException();
    }

    /** 🔴 계정별 격리 — 남의 업체코드 429 로 내 계정이 멈추면 안 된다(PLAN 2609_46 D1). */
    @Test
    void check_otherVendor_unaffectedByTrip() {
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(Clock.fixed(T0, UTC));

        guard.trip(VENDOR);

        assertThatCode(() -> guard.check(OTHER_VENDOR)).doesNotThrowAnyException();
    }
}
