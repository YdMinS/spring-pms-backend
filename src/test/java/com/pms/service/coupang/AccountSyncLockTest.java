package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.service.coupang.AccountSyncLock.Lease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.pms.service.coupang.AccountSyncLock.SyncWork.ORDER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채널별 동기화 락 단위 테스트 — Clock 을 밀어 회수(D4)를 검증한다(Spring 컨텍스트·목 없음).
 */
class AccountSyncLockTest {

    private static final Instant T0 = Instant.parse("2026-09-15T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final AccountSyncLock lock = new AccountSyncLock(new CoupangProperties(), clock);

    @Test
    void tryAcquire_whenHeld_returnsNull() {
        assertThat(lock.tryAcquire(ORDER, 1L)).isNotNull();

        assertThat(lock.tryAcquire(ORDER, 1L)).isNull();
    }

    @Test
    void close_thenAcquireAgain_succeeds() {
        Lease lease = lock.tryAcquire(ORDER, 1L);
        lease.close();

        assertThat(lock.tryAcquire(ORDER, 1L)).isNotNull();
    }

    /** D4 — 10분 넘게 쥔 락은 다음 요청이 뺏는다. 안 그러면 그 채널은 재시작까지 계속 거절된다. */
    @Test
    void staleLease_isTakenOver() {
        lock.tryAcquire(ORDER, 1L);

        clock.advance(Duration.ofMinutes(11));

        assertThat(lock.tryAcquire(ORDER, 1L)).isNotNull();
    }

    /** 🔴 회수당한 원 주인이 뒤늦게 끝나며 새 주인의 락을 풀어버리면 안 된다(Lease#close 의 remove(key, token)). */
    @Test
    void staleHolder_closeDoesNotFreeNewOwner() {
        Lease first = lock.tryAcquire(ORDER, 1L);
        clock.advance(Duration.ofMinutes(11));
        Lease second = lock.tryAcquire(ORDER, 1L);
        assertThat(second).isNotNull();

        first.close();

        assertThat(lock.tryAcquire(ORDER, 1L)).isNull();
    }

    /** D1 — 계정 단위라 다른 채널은 서로 막지 않는다(테넌트 락이면 병렬 동기화가 죽는다). */
    @Test
    void differentAccounts_doNotBlock() {
        lock.tryAcquire(ORDER, 1L);

        assertThat(lock.tryAcquire(ORDER, 2L)).isNotNull();
    }
}
