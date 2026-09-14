package com.pms.service.coupang;

import com.pms.exception.CoupangRateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coupang 429 cooldown circuit, per vendor.
 *
 * Coupang's guidance: after a 429, stop calling for ~10 minutes and the limit clears itself;
 * retrying through it risks a permanent IP block. This guard short-circuits calls during that
 * window so a user re-clicking "sync" cannot extend the ban.
 *
 * <p>🔴 Keyed by vendorId, NOT process-wide. Coupang enforces the 429 rate limit per vendor
 * (업체코드), not per caller IP — the IP-based limit is the separate 403 block (20 errors / 5s).
 * An earlier comment here claimed the opposite; prod logs on 2026-09-14 showed the cost:
 * one vendor's 429 killed another vendor's sync in the same cycle (FEATURE_2609_46 / PLAN D1).
 *
 * <p>Expired entries are never swept: the map is keyed by vendor and an account count in the
 * tens-to-hundreds cannot grow it meaningfully.
 */
@Component
public class CoupangRateLimitGuard {

    static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public CoupangRateLimitGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * 차단창이 열려 있으면 쿠팡을 치지 않고 즉시 실패시킨다.
     *
     * @param vendorId 업체코드 — 차단은 이 계정에만 적용된다(D1·D2)
     */
    public void check(String vendorId) {
        Instant until = blockedUntil.get(vendorId);
        if (until != null && clock.instant().isBefore(until)) {
            throw new CoupangRateLimitedException(until);
        }
    }

    /**
     * 429 수신 시 해당 업체코드의 쿨다운 시작.
     *
     * @return 차단 종료 시각 — 호출자가 재시도 가능 시각을 그대로 실어 던질 수 있게 돌려준다
     *         (PLAN 2609_31 D9-1. COOLDOWN 계산이 호출부에 복제되지 않게 하려는 것이다)
     */
    public Instant trip(String vendorId) {
        Instant until = clock.instant().plus(COOLDOWN);
        blockedUntil.put(vendorId, until);
        return until;
    }
}
