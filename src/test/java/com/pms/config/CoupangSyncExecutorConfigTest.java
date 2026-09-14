package com.pms.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계정 동기화 전용 풀 — 동시 실행 수 설정과 스레드 이름(로그 grep 수단) 검증.
 */
class CoupangSyncExecutorConfigTest {

    private final CoupangSyncExecutorConfig config = new CoupangSyncExecutorConfig();

    @Test
    void executor_usesConfiguredConcurrency() {
        CoupangProperties properties = new CoupangProperties();
        properties.setSyncConcurrency(4);

        ExecutorService executor = config.coupangSyncExecutor(properties);

        try {
            assertThat(((ThreadPoolExecutor) executor).getCorePoolSize()).isEqualTo(4);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void executor_namesThreadsForLogGrep() throws Exception {
        ExecutorService executor = config.coupangSyncExecutor(new CoupangProperties());
        AtomicReference<String> threadName = new AtomicReference<>();

        try {
            executor.submit(() -> threadName.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertThat(threadName.get()).startsWith("coupang-sync-");
    }
}
