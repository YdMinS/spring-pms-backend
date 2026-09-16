package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-vendor outbound call budget for the Coupang OpenAPI.
 *
 * Coupang's documented limit is 5 requests/second per vendorId. Prod logs on 2026-09-14 showed
 * 8 calls in 650ms (≈12/s) on a single sync cycle, which is what produced the 429s — the problem
 * was never total volume (a cycle is ~10 calls) but the burst.
 *
 * <p>Token bucket: {@code callsPerSecond} refill, {@code callBurst} capacity. The bucket cannot tell
 * a short job from a long one — it only sees whether the caller has been idle. A call that follows
 * an idle gap finds a token waiting and goes out immediately; back-to-back calls line up at
 * {@code 1 / callsPerSecond}. A long sync that stalls on a slow Coupang response counts as idle too,
 * so it gets the same free tokens (prod, 2026-09-14). Blocking is intentional — the caller is a sync
 * that is allowed to take seconds.
 *
 * <p>🔴 Capacity plus refill is the worst case for any one-second window, so keep
 * {@code callBurst + callsPerSecond < 5}. Sitting exactly ON the documented limit is not a margin:
 * prod paced calls 250ms apart (4/s + burst 1 = 5) and still took a 429 on 2026-09-16.
 *
 * <p>🔴 In-memory and therefore single-instance only. Running two app instances silently doubles
 * the effective rate (PLAN D11) — a distributed limiter must land before any horizontal scaling.
 * This is not theoretical: the dev and prod containers share a host AND a vendor id, so once the
 * 15-minute order-sync schedule landed they fired on the same cron tick and Coupang saw 8/s
 * (2026-09-16 09:15 KST). Until one process owns the outbound calls, every marketplace schedule
 * stays off on dev ({@code application-dev.yml}).
 */
@Component
public class CoupangCallBudget {

    /** 대기 방식을 갈아끼우기 위한 seam — 테스트가 실제로 잠들지 않게 한다. */
    @FunctionalInterface
    public interface Waiter {
        void await(Duration duration) throws InterruptedException;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final CoupangProperties properties;
    private final Clock clock;
    private final Waiter waiter;

    /** Spring 이 쓰는 생성자 — 기본 Waiter 는 Thread.sleep 이다. */
    @Autowired
    public CoupangCallBudget(CoupangProperties properties, Clock clock) {
        this(properties, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    /** 테스트 전용 — 실제로 잠들지 않는 Waiter 를 끼운다. */
    CoupangCallBudget(CoupangProperties properties, Clock clock, Waiter waiter) {
        this.properties = properties;
        this.clock = clock;
        this.waiter = waiter;
    }

    /**
     * 토큰 1개를 쓸 수 있을 때까지 블로킹한다.
     *
     * <p>⚠️ 대기는 lock 밖에서 한다 — 잠든 채 lock 을 쥐면 같은 업체코드로 들어온 다른 스레드가
     * 통째로 줄을 선다.
     */
    public void acquire(String vendorId) {
        Bucket bucket = buckets.computeIfAbsent(vendorId,
                key -> new Bucket(clock.instant(), properties.getCallBurst()));
        while (true) {
            Duration wait = bucket.tryConsume(clock.instant(),
                    properties.getCallsPerSecond(), properties.getCallBurst());
            if (wait.isZero() || wait.isNegative()) {
                return;
            }
            try {
                waiter.await(wait);
            } catch (InterruptedException e) {
                // 인터럽트를 삼키면 종료 신호가 사라진다 — 플래그를 되살리고 즉시 중단한다.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("쿠팡 호출 대기 중 인터럽트되었습니다.", e);
            }
        }
    }

    /** 업체코드 하나의 토큰 상태. 모든 변경은 인스턴스 락 안에서만 일어난다. */
    private static final class Bucket {
        private double tokens;
        private Instant last;

        /** 새 업체코드는 버스트를 가득 채운 채 시작한다 — 첫 호출이 이유 없이 대기하지 않게 한다. */
        private Bucket(Instant now, int burst) {
            this.tokens = burst;
            this.last = now;
        }

        /**
         * 경과분을 충전한 뒤 토큰 1개를 쓰려 시도한다.
         *
         * @return 소비했으면 {@link Duration#ZERO}, 아니면 부족분을 채우는 데 필요한 대기 시간
         */
        private synchronized Duration tryConsume(Instant now, double ratePerSecond, int burst) {
            double elapsedSeconds = Math.max(0, Duration.between(last, now).toNanos() / 1_000_000_000d);
            last = now;
            tokens = Math.min(burst, tokens + elapsedSeconds * ratePerSecond);
            if (tokens >= 1) {
                tokens -= 1;
                return Duration.ZERO;
            }
            double missing = 1 - tokens;
            return Duration.ofNanos((long) Math.ceil(missing / ratePerSecond * 1_000_000_000d));
        }
    }
}
