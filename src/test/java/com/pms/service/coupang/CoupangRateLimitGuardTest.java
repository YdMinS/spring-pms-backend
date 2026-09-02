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
 * 429 쿨다운 서킷 단위 테스트 — Clock.fixed 로 시간을 밀어 검증한다(Spring 컨텍스트 없음).
 */
class CoupangRateLimitGuardTest {

    private static final Instant T0 = Instant.parse("2026-09-02T00:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void check_passes_whenNeverTripped() {
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(Clock.fixed(T0, UTC));

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void check_throws_duringCooldown() {
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(Clock.fixed(T0, UTC));

        guard.trip();

        assertThatThrownBy(guard::check).isInstanceOf(CoupangRateLimitedException.class);
    }

    @Test
    void check_passes_afterCooldownElapsed() {
        MutableClock clock = new MutableClock(T0);
        CoupangRateLimitGuard guard = new CoupangRateLimitGuard(clock);

        guard.trip();
        clock.advance(Duration.ofMinutes(11));

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    /** 쿨다운 경과를 표현하려면 시각이 움직여야 한다 — Clock.fixed 는 고정이라 쓸 수 없다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
