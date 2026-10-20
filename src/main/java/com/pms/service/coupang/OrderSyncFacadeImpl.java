package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * {@link OrderSyncFacade} implementation.
 *
 * syncOne takes the account's lock and hands the account to the {@link OrderSyncAdapter} of its marketplace
 * (FEATURE_2610_23 / D21) — {@link CoupangOrderSyncAdapter} · {@link ElevenstOrderSyncAdapter}. syncEach isolates
 * every account with try/catch so one account failing does not roll back the rest, and runs the accounts
 * <b>concurrently</b> on a dedicated pool (coupangSyncExecutor) — the Coupang call budget is per vendor code, so the
 * cycle time does not grow with the account count (FEATURE_2609_46 / PLAN D6).
 *
 * ⚠️ This facade deliberately has no @Transactional. A shared transaction would make the inner
 * {@link CoupangOrderSyncService}/{@link CoupangReturnSyncService} (each @Transactional) join it (REQUIRED), and one
 * account throwing (e.g. a Coupang 504) would mark the shared transaction rollback-only. Even with the exception
 * swallowed by try/catch, the commit would then fail with UnexpectedRollbackException and roll back the accounts
 * that succeeded. Without @Transactional each account's inner calls commit / roll back in their own transactions.
 * (The inner services read only the account's scalar fields — no lazy association — so a detached account is safe.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSyncFacadeImpl implements OrderSyncFacade {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CoupangProperties coupangProperties;
    private final CoupangOrderSyncService coupangOrderSyncService;
    /** FEATURE_2610_23 / D21: one order sync round per marketplace. */
    private final List<OrderSyncAdapter> orderSyncAdapters;
    /**
     * 계정 병렬 동기화용 전용 풀({@link com.pms.config.CoupangSyncExecutorConfig}).
     * 앱 전체에서 유일한 {@link ExecutorService} 빈이라 타입 주입으로 충분하다 — 두 번째 빈이 생기면
     * {@code @Qualifier} + {@code lombok.config} 의 {@code copyableAnnotations} 가 세트로 필요하다
     * (설정 파일이 없으면 {@code @RequiredArgsConstructor} 가 어노테이션을 조용히 버린다).
     */
    private final ExecutorService coupangSyncExecutor;
    /** 채널별 동기화 락(FEATURE_2609_48 / D1·D8) — 같은 채널을 동시에 두 벌 돌리지 않는다. */
    private final AccountSyncLock accountSyncLock;
    /**
     * FEATURE_2610_23 / D3 · D14: a single-account sync ({@code sync(accountId)}) of a channel whose marketplace has
     * no adapter runs the Coupang round, as before this frame existed.
     */
    private final CoupangOrderSyncAdapter coupangOrderSyncAdapter;

    @Override
    public OrderSyncResult sync(Long accountId) {
        return sync(accountId, OrderSyncPreset.QUICK);
    }

    @Override
    public OrderSyncResult sync(Long accountId, OrderSyncPreset preset) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        // 🔴 여기는 웹 요청 스레드다 — 풀 스레드(syncOneIsolated)와 달리 이전 값을 복원한다(PLAN 2609_46 D14).
        // clear() 로 통일하면 컨트롤러가 동기화 직후 같은 스레드에서 도는 목록 조회(OrderController#sync)가
        // 테넌트 없이 돌고, TenantIdentifierResolver 가 -1 을 돌려 예외 없이 빈 목록이 응답에 실린다.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());
            // scheduled=false — 수동 회차라 실패·부분 실패를 그대로 기록한다(D13).
            return syncOne(account, preset, false);   // 단건은 격리 없이 예외 전파
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    @Override
    public OrderSyncResult syncBySeller(Long sellerId) {
        return syncEach(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId),
                OrderSyncPreset.QUICK, false);
    }

    @Override
    public OrderSyncResult syncAll() {
        return syncEach(marketplaceAccountRepository.findByIsActiveTrue(), OrderSyncPreset.QUICK, false);
    }

    @Override
    public OrderSyncResult syncAll(OrderSyncPreset preset) {
        // 스케줄 경로 — scheduled=true 로 실패 낙인을 끈다(FEATURE_2609_49 / D13).
        return syncEach(marketplaceAccountRepository.findByIsActiveTrue(), preset, true);
    }

    @Override
    public OrderSyncResult syncPeriod(Long accountId, LocalDate from, LocalDate to) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        if (!Platform.COUPANG.equals(account.getPlatform())) {
            throw new IllegalArgumentException("쿠팡 계정만 기간 조회를 지원합니다. accountId=" + accountId);
        }
        SyncWindow window = new SyncWindow(from, to);      // 검증은 record 생성자

        // 과거 달 불러오기도 정기 동기화와 같은 열쇠를 쓴다(FEATURE_2609_48 / D6) — 둘 다 같은 채널에
        // 주문을 적재하므로 동시에 돌면 같은 주문을 두 번 쓴다.
        AccountSyncLock.Lease lease = accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, accountId);
        if (lease == null) {
            log.info("Period sync skipped (already running): account={}", accountId);
            return OrderSyncResult.skipped();
        }
        try (lease) {
            // syncOne 을 재사용하지 않는다 — 취소 보정과 상태 기록이 붙어 있다(D4·D5).
            Long previousTenant = TenantContext.get();
            try {
                TenantContext.set(account.getTenantId());
                SyncResult orders = coupangOrderSyncService.syncAccount(account, window);
                if (!orders.failedStatuses().isEmpty()) {
                    log.warn("Period sync partial: account={} window={} failedStatuses={}",
                            accountId, window, orders.failedStatuses());
                }
                // 취소 보정 없음(D4) → canceledUpdated = 0. 상태 기록 없음(D5).
                return new OrderSyncResult(LocalDateTime.now(), orders.newCount(), orders.updatedCount(), 0, 0);
            } finally {
                if (previousTenant != null) {
                    TenantContext.set(previousTenant);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }

    /**
     * Syncs the accounts in isolation and sums the results (the marketplaces with an {@link OrderSyncAdapter}; one
     * account failing is logged and the rest continue — FEATURE_2610_17 / D9 · FEATURE_2610_23 / D14).
     *
     * <p>Takes a preset (FEATURE_2609_49 / D7) — the seller / all syncs the screen calls use {@code QUICK}; only the
     * early-morning reconcile schedule uses {@code RECONCILE}. The preset decides only the statuses and the window of
     * the order lookup; the other steps run the same.
     *
     * @param scheduled whether this is a scheduled round — {@code true} records no failure or partial failure (D13)
     */
    private OrderSyncResult syncEach(List<MarketplaceAccount> accounts, OrderSyncPreset preset,
                                     boolean scheduled) {
        long startedAt = System.nanoTime();
        List<MarketplaceAccount> targets = accounts.stream()
                .filter(a -> isSyncTarget(a, scheduled))
                .toList();
        int processed = targets.size();

        // 계정마다 동시에 던진다 — 쿠팡 예산이 업체코드별이라 서로의 몫을 먹지 않는다(PLAN 2609_46 D6).
        // ❌ parallelStream() 금지 — 공용 ForkJoinPool 이라 동시 실행 수를 제어할 수 없고 다른 작업과 섞인다.
        List<Future<OrderSyncResult>> futures = targets.stream()
                .map(account -> coupangSyncExecutor.submit(() -> syncOneIsolated(account, preset, scheduled)))
                .toList();

        // 합산은 제출 스레드에서 순차로 한다 — OrderSyncResult 는 immutable record 라 값은 안전하지만
        // 누산 변수를 여러 스레드가 건드리면 갱신을 잃는다.
        OrderSyncResult total = OrderSyncResult.empty();
        for (Future<OrderSyncResult> future : futures) {
            OrderSyncResult one = await(future);
            if (one != null) {
                total = total.plus(one);
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        // accounts = the number of channels this cycle handled (those that passed isSyncTarget), not every account
        // passed in; `skipped` of them were blocked by the lock and called no marketplace (2609_48 D5) — the
        // number actually run is accounts - skipped.
        // Since the parallelization (2609_46 D6) this value does not grow with the account count — once it does,
        // the concurrency limit (oklyx.coupang.sync-concurrency) is saturated.
        // 🔴 The preset goes into the log (2609_49) — without telling the nightly full round from the 15-minute round,
        // later tuning (which one takes how long) loses its basis.
        log.info("Order sync cycle done: preset={} accounts={} skipped={} elapsedMs={}",
                preset, processed, total.skippedAccounts(), elapsedMs);
        if (elapsedMs > coupangProperties.getSyncCycleWarnSeconds() * 1000L) {
            log.warn("[COUPANG][ALERT] 동기화 사이클 {}초 초과 — accounts={} elapsedMs={}",
                    coupangProperties.getSyncCycleWarnSeconds(), processed, elapsedMs);
        }
        return total;
    }

    /**
     * FEATURE_2610_23 / D14: a marketplace with an {@link OrderSyncAdapter}; a scheduled round also needs
     * {@link OrderSyncAdapter#runsInScheduledRound()} (11st on dev · local skips — FEATURE_2610_17 / D9 · D10).
     */
    private boolean isSyncTarget(MarketplaceAccount account, boolean scheduled) {
        return adapterFor(account)
                .map(adapter -> !scheduled || adapter.runsInScheduledRound())
                .orElse(false);
    }

    /** The adapter whose platform matches the account — same lookup as {@code ClaimActionServiceImpl.resolve()}. */
    private Optional<OrderSyncAdapter> adapterFor(MarketplaceAccount account) {
        return orderSyncAdapters.stream()
                .filter(adapter -> adapter.platform().equals(account.getPlatform()))
                .findFirst();
    }

    /**
     * 한 계정을 풀 스레드에서 격리 실행한다 — 실패는 로그만 남기고 {@code null} 을 돌려 다른 계정을 죽이지 않는다.
     *
     * <p>🔴 {@code finally} 에서 <b>복원이 아니라</b> {@link TenantContext#clear()} 다(PLAN 2609_46 D8).
     * 풀 스레드에 남아 있는 "이전 값"은 직전에 그 스레드를 쓴 <b>남의 테넌트</b> 것이라 복원하면
     * 교차 테넌트 유출 경로가 된다. 웹 요청 스레드에서 도는 {@link #sync(Long, OrderSyncPreset)} 는
     * 반대로 복원해야 한다 — 두 방식이 다른 이유는 그쪽 주석 참조(D14).
     *
     * <p>🔴 실패 계정은 {@code OrderSyncResult.empty()} 가 아니라 {@code null} 이다. {@code plus()} 가
     * {@code other.syncedAt} 을 취하므로 empty 를 더하면 마지막 계정이 실패했을 때 합계의 동기화 시각이
     * 실제로 동기화되지 않은 {@code now()} 로 덮인다.
     */
    private OrderSyncResult syncOneIsolated(MarketplaceAccount account, OrderSyncPreset preset,
                                            boolean scheduled) {
        try {
            TenantContext.set(account.getTenantId());
            return syncOne(account, preset, scheduled);
        } catch (Exception e) {
            log.warn("Order sync failed for account={}, isolated and continue", account.getId(), e);
            return null;
        } finally {
            TenantContext.clear();
        }
    }

    /** Future 결과를 꺼낸다. 태스크가 이미 격리하므로 정상 경로에서 ExecutionException 은 오지 않는다. */
    private OrderSyncResult await(Future<OrderSyncResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 동기화가 중단되었습니다", e);
        } catch (ExecutionException e) {
            log.warn("Order sync task failed outside isolation", e.getCause());
            return null;
        }
    }

    /**
     * One account: the {@link OrderSyncAdapter} of its marketplace (FEATURE_2610_23 / D21).
     *
     * <p>Runs under the lock — when this channel is already running it is skipped without calling the marketplace
     * (FEATURE_2609_48 / D8). Both the single ({@code sync(accountId)}) and the all / seller
     * ({@code syncEach → syncOneIsolated}) paths pass here, so the lock sits in this one place only.
     */
    private OrderSyncResult syncOne(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        AccountSyncLock.Lease lease =
                accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, account.getId());
        if (lease == null) {
            // 🔴 SyncStatusRecorder 를 부르지 않는다 — 건너뛴 회차는 성공도 실패도 아니다. 기록을 남기면
            // 화면의 "마지막 동기화" 시각이 실제로 조회하지 않은 시각으로 밀리고, lastOrderSyncAt 은 다음
            // 쿠팡 조회 창을 정하는 앵커라 조회 구간까지 어긋난다.
            log.info("Order sync skipped (already running): account={} heldSec={}",
                    account.getId(),
                    accountSyncLock.heldSeconds(AccountSyncLock.SyncWork.ORDER, account.getId()));
            return OrderSyncResult.skipped();
        }
        // try-with-resources 라 본문이 예외를 던져도 빠져나가기 전에 풀린다 —
        // "실패했으니 곧바로 다시 누른다" 가 성립해야 한다.
        try (lease) {
            return syncOneLocked(account, preset, scheduled);
        }
    }

    /** The body under the lock — the account's round runs in its marketplace's adapter (FEATURE_2610_23 / D21). */
    private OrderSyncResult syncOneLocked(MarketplaceAccount account, OrderSyncPreset preset,
                                          boolean scheduled) {
        // 🔴 테넌트는 호출자가 세팅한다 — 여기서는 저장·복원하지 않는다(PLAN 2609_46 D8·D14).
        //   - syncEach → syncOneIsolated: 풀 스레드라 finally 에서 clear() (남의 테넌트 복원 금지)
        //   - sync(accountId): 웹 요청 스레드라 finally 에서 이전 값 복원 (직후 목록 조회가 빈다)
        // 계정의 테넌트를 쓰는 이유는 그대로다 — 저장되는 order_line/shopping_list_item(@TenantId) 이
        // 트리거(웹 관리자·SecurityContext 없는 배치)와 무관하게 그 계정의 테넌트로 들어가야 한다.
        // ⚠️ syncPeriod 는 syncOne 을 부르지 않는 별도 경로라 자체 저장·복원을 그대로 둔다.
        return adapterFor(account)
                .orElse(coupangOrderSyncAdapter)
                .sync(account, preset, scheduled);
    }
}
