package com.pms.service.coupang;

import com.pms.exception.CoupangRateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coupang 429 cooldown circuit.
 *
 * Coupang's guidance: after a 429, stop all calls for ~10 minutes and the limit clears itself;
 * retrying through it risks a permanent IP block. This guard short-circuits calls during that
 * window so a user re-clicking "sync" cannot extend the ban.
 *
 * Process-wide (not per vendor) because the limit is enforced on the caller's IP.
 */
@Component
public class CoupangRateLimitGuard {

    static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);
    private final Clock clock;

    public CoupangRateLimitGuard(Clock clock) {
        this.clock = clock;
    }

    /** 차단창이 열려 있으면 쿠팡을 치지 않고 즉시 실패시킨다. */
    public void check() {
        Instant until = blockedUntil.get();
        if (clock.instant().isBefore(until)) {
            throw new CoupangRateLimitedException(until);
        }
    }

    /**
     * 429 수신 시 쿨다운 시작.
     *
     * @return 차단 종료 시각 — 호출자가 재시도 가능 시각을 그대로 실어 던질 수 있게 돌려준다
     *         (PLAN 2609_31 D9-1. COOLDOWN 계산이 호출부에 복제되지 않게 하려는 것이다)
     */
    public Instant trip() {
        Instant until = clock.instant().plus(COOLDOWN);
        blockedUntil.set(until);
        return until;
    }
}
