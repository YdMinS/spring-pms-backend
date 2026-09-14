package com.pms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 계정 동기화 전용 스레드풀 (FEATURE_2609_46 / PLAN D6).
 *
 * <p>쿠팡 429 한도가 업체코드(vendorId)별이라 계정을 동시에 돌려도 서로의 예산을 먹지 않는다 —
 * 01 이 쿨다운과 토큰버킷을 업체코드별로 분리했기 때문이다. 동시 실행 수를 제한하는 이유는 쿠팡이
 * 아니라 우리 쪽이다: DB 커넥션 풀(01 에서 20)과 403(IP 단위, 5초 20에러) 때문이다.
 *
 * <p>스레드 이름 접두사 {@code coupang-sync-} 는 로그에서 병렬 동작 여부를 눈으로 확인하는 유일한
 * 수단이다 — 바꾸지 말 것(prod 검증 절차가 이 문자열을 grep 한다).
 *
 * <p>❌ {@code @Async} 를 facade 에 붙이지 말 것 — 호출부가 결과를 동기로 받는 계약이다
 * ({@code OrderController} 는 동기화 결과와 목록을 한 응답에 싣는다).
 */
@Configuration
public class CoupangSyncExecutorConfig {

    /**
     * 계정 병렬 동기화용 고정 크기 풀.
     *
     * <p>⚠️ 앱 전체에서 유일한 {@link ExecutorService} 빈이라 주입은 타입으로 한다. 두 번째
     * {@code ExecutorService} 빈이 생기는 날에는 {@code @Qualifier} 와 {@code lombok.config} 의
     * {@code lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier} 가
     * <b>세트로</b> 필요하다 — 이 저장소에는 {@code lombok.config} 가 없어 {@code @RequiredArgsConstructor}
     * 가 필드의 {@code @Qualifier} 를 생성자로 복사하지 않고 조용히 무시한다.
     */
    @Bean(name = "coupangSyncExecutor", destroyMethod = "shutdown")
    public ExecutorService coupangSyncExecutor(CoupangProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "coupang-sync-" + sequence.getAndIncrement());
            thread.setDaemon(true);   // 종료 시 남은 태스크가 JVM 을 붙잡지 않게 한다
            return thread;
        };
        return Executors.newFixedThreadPool(properties.getSyncConcurrency(), threadFactory);
    }
}
