package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.claim.ClaimOrderBackfillService;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.inquiry.InquirySyncAdapter;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * {@link OrderSyncFacade} 구현.
 *
 * syncOne = ordersheets(Phase2) → returnRequests 취소 보정(§A) → 미완결 추적(05) → 교환 적재(06) →
 * 클레임 주문 백필(04) →
 * 고객문의 적재(2609_23 D12) 순서로 한 계정을 동기화한다.
 * syncEach 는 계정마다 try/catch 로 격리해 한 계정 실패가 전체를 롤백하지 않게 하며, 계정들을 전용
 * 풀(coupangSyncExecutor)에서 <b>동시에</b> 돌린다 — 쿠팡 호출 예산이 업체코드별이라 계정 수가 늘어도
 * 사이클 시간이 비례해 늘지 않는다(FEATURE_2609_46 / PLAN D6).
 *
 * ⚠️ 이 파사드는 의도적으로 @Transactional 을 두지 않는다. 공유 트랜잭션을 열면 내부
 * {@link CoupangOrderSyncService}/{@link CoupangReturnSyncService}(각자 @Transactional)가 그 트랜잭션에
 * 합류(REQUIRED)하고, 한 계정이 예외(예: 쿠팡 504)를 던지면 공유 트랜잭션이 rollback-only 로 마킹된다.
 * 그러면 try/catch 로 예외를 삼켜도 커밋 시 UnexpectedRollbackException 이 나고 성공한 계정까지 롤백된다.
 * @Transactional 없이 두면 계정별 내부 호출이 각각 독립 트랜잭션으로 커밋/롤백돼 격리가 보장된다.
 * (내부 서비스는 account 의 scalar 필드만 사용 — lazy 연관 접근 없음 — 이라 계정이 detached 여도 안전.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSyncFacadeImpl implements OrderSyncFacade {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CoupangProperties coupangProperties;
    private final CoupangOrderSyncService coupangOrderSyncService;
    private final CoupangReturnSyncService coupangReturnSyncService;
    private final SyncStatusRecorder syncStatusRecorder;
    private final ClaimOrderBackfillService claimOrderBackfillService;
    /** 교환 클레임 동기화 어댑터(D21). 플랫폼 미지원(네이버)·빈 리스트(local/test)면 조용히 건너뛴다. */
    private final List<ClaimSyncAdapter> claimSyncAdapters;
    /** 고객문의 동기화 어댑터(2609_23 D12·D19). 클레임 어댑터와 같은 자세로 조용히 건너뛴다. */
    private final List<InquirySyncAdapter> inquirySyncAdapters;
    /**
     * 계정 병렬 동기화용 전용 풀({@link com.pms.config.CoupangSyncExecutorConfig}).
     * 앱 전체에서 유일한 {@link ExecutorService} 빈이라 타입 주입으로 충분하다 — 두 번째 빈이 생기면
     * {@code @Qualifier} + {@code lombok.config} 의 {@code copyableAnnotations} 가 세트로 필요하다
     * (설정 파일이 없으면 {@code @RequiredArgsConstructor} 가 어노테이션을 조용히 버린다).
     */
    private final ExecutorService coupangSyncExecutor;

    @Override
    public OrderSyncResult sync(Long accountId) {
        return sync(accountId, OrderSyncScope.FULL);
    }

    @Override
    public OrderSyncResult sync(Long accountId, OrderSyncScope scope) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        // 🔴 여기는 웹 요청 스레드다 — 풀 스레드(syncOneIsolated)와 달리 이전 값을 복원한다(PLAN 2609_46 D14).
        // clear() 로 통일하면 컨트롤러가 동기화 직후 같은 스레드에서 도는 목록 조회(OrderController#sync)가
        // 테넌트 없이 돌고, TenantIdentifierResolver 가 -1 을 돌려 예외 없이 빈 목록이 응답에 실린다.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());
            return syncOne(account, scope);   // 단건은 격리 없이 예외 전파
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
        return syncEach(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId));
    }

    @Override
    public OrderSyncResult syncAll() {
        return syncEach(marketplaceAccountRepository.findByIsActiveTrue());
    }

    @Override
    public OrderSyncResult syncPeriod(Long accountId, LocalDate from, LocalDate to) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        if (!Platform.COUPANG.equals(account.getPlatform())) {
            throw new IllegalArgumentException("쿠팡 계정만 기간 조회를 지원합니다. accountId=" + accountId);
        }
        SyncWindow window = new SyncWindow(from, to);      // 검증은 record 생성자

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
            return new OrderSyncResult(LocalDateTime.now(), orders.newCount(), orders.updatedCount(), 0);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * 계정 목록을 격리 동기화해 합산 (COUPANG 만, 한 계정 실패는 로그 후 계속).
     *
     * 범위는 항상 {@link OrderSyncScope#FULL} 이다(PLAN 2609_16 D4) — 셀러/전체 동기화는 어느 화면이
     * 불렀는지 구분 없이 도는 호출이라 범위를 실어 보낼 자리가 아니다.
     */
    private OrderSyncResult syncEach(List<MarketplaceAccount> accounts) {
        long startedAt = System.nanoTime();
        List<MarketplaceAccount> targets = accounts.stream()
                .filter(a -> Platform.COUPANG.equals(a.getPlatform()))
                .toList();
        int processed = targets.size();

        // 계정마다 동시에 던진다 — 쿠팡 예산이 업체코드별이라 서로의 몫을 먹지 않는다(PLAN 2609_46 D6).
        // ❌ parallelStream() 금지 — 공용 ForkJoinPool 이라 동시 실행 수를 제어할 수 없고 다른 작업과 섞인다.
        List<Future<OrderSyncResult>> futures = targets.stream()
                .map(account -> coupangSyncExecutor.submit(() -> syncOneIsolated(account)))
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
        // accounts = 이 사이클이 실제로 돈 쿠팡 계정 수(플랫폼 필터를 통과한 수)다. 넘겨받은 전체 계정 수가 아니다.
        // 병렬화(2609_46 D6) 이후 이 값은 계정 수에 비례하지 않는다 — 비례하기 시작하면 동시 실행 수
        // (oklyx.coupang.sync-concurrency)가 포화됐다는 뜻이다.
        log.info("Order sync cycle done: accounts={} elapsedMs={}", processed, elapsedMs);
        if (elapsedMs > coupangProperties.getSyncCycleWarnSeconds() * 1000L) {
            log.warn("[COUPANG][ALERT] 동기화 사이클 {}초 초과 — accounts={} elapsedMs={}",
                    coupangProperties.getSyncCycleWarnSeconds(), processed, elapsedMs);
        }
        return total;
    }

    /**
     * 한 계정을 풀 스레드에서 격리 실행한다 — 실패는 로그만 남기고 {@code null} 을 돌려 다른 계정을 죽이지 않는다.
     *
     * <p>🔴 {@code finally} 에서 <b>복원이 아니라</b> {@link TenantContext#clear()} 다(PLAN 2609_46 D8).
     * 풀 스레드에 남아 있는 "이전 값"은 직전에 그 스레드를 쓴 <b>남의 테넌트</b> 것이라 복원하면
     * 교차 테넌트 유출 경로가 된다. 웹 요청 스레드에서 도는 {@link #sync(Long, OrderSyncScope)} 는
     * 반대로 복원해야 한다 — 두 방식이 다른 이유는 그쪽 주석 참조(D14).
     *
     * <p>🔴 실패 계정은 {@code OrderSyncResult.empty()} 가 아니라 {@code null} 이다. {@code plus()} 가
     * {@code other.syncedAt} 을 취하므로 empty 를 더하면 마지막 계정이 실패했을 때 합계의 동기화 시각이
     * 실제로 동기화되지 않은 {@code now()} 로 덮인다.
     */
    private OrderSyncResult syncOneIsolated(MarketplaceAccount account) {
        try {
            TenantContext.set(account.getTenantId());
            return syncOne(account, OrderSyncScope.FULL);
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
     * 한 계정: ordersheets 먼저 → 취소 보정(이미 적재된 주문 위에 보정).
     *
     * {@code scope} 는 ordersheets 가 조회할 상태만 좁힌다 — 취소 보정과 상태 기록은 범위와 무관하게
     * 그대로 돈다(PLAN 2609_16 D5·D6).
     */
    private OrderSyncResult syncOne(MarketplaceAccount account, OrderSyncScope scope) {
        // 🔴 테넌트는 호출자가 세팅한다 — 여기서는 저장·복원하지 않는다(PLAN 2609_46 D8·D14).
        //   - syncEach → syncOneIsolated: 풀 스레드라 finally 에서 clear() (남의 테넌트 복원 금지)
        //   - sync(accountId): 웹 요청 스레드라 finally 에서 이전 값 복원 (직후 목록 조회가 빈다)
        // 계정의 테넌트를 쓰는 이유는 그대로다 — 저장되는 order_line/shopping_list_item(@TenantId) 이
        // 트리거(웹 관리자·SecurityContext 없는 배치)와 무관하게 그 계정의 테넌트로 들어가야 한다.
        // ⚠️ syncPeriod 는 syncOne 을 부르지 않는 별도 경로라 자체 저장·복원을 그대로 둔다.
        SyncResult orders;
        try {
            orders = coupangOrderSyncService.syncAccount(account, scope);
        } catch (RuntimeException e) {
            syncStatusRecorder.recordFailure(account.getId(), e);
            throw e;                    // 격리는 syncEach 담당, 단건은 전파(D4)
        }

        // 00(D15) 이후 일부 상태만 실패하면 예외가 아니라 failedStatuses 로 돌아온다(전 상태 실패는 예외).
        // 이걸 무시하고 SUCCESS 를 찍으면 2026-09-02 사고 계정이 "정상"으로 낙인된다(D18).
        String orderPartial = null;
        if (!orders.failedStatuses().isEmpty()) {
            // 일부 상태만 실패 — 성공한 상태는 이미 커밋됐다(PLAN D15).
            // 00(D17) 이 넣은 WARN 로그다. 지우지 말 것 — 사후 추적의 유일한 근거.
            log.warn("Order sync partial: account={} failedStatuses={}",
                    account.getId(), orders.failedStatuses());
            orderPartial = "주문 조회 일부 실패 — 상태: " + orders.failedStatuses().stream()
                    .map(Enum::name).collect(Collectors.joining(", "));
        }

        CancelSyncResult cancels;
        try {
            cancels = coupangReturnSyncService.syncCancels(account);
        } catch (RuntimeException e) {
            // Orders landed but cancellations did not: canceled lines can still look purchasable,
            // so this is NOT a success (PLAN D8). 취소 보정 사유를 앞에 둔다(더 위험한 쪽).
            String reason = "취소 보정 실패 — " + SyncStatusRecorder.summarize(e)
                    + (orderPartial == null ? "" : " / " + orderPartial);
            syncStatusRecorder.recordPartial(account.getId(), reason, orderPartial == null, false);
            throw e;
        }

        // 확정 순서: 취소 보정 → 추적 → 백필. 추적 슬라이스도 클레임을 적재하므로, 백필이 뒤에
        // 있어야 그때 새로 생긴 미연결 건까지 같은 회차에 처리된다(2609_18 05 Step 8).
        try {
            coupangReturnSyncService.trackOpenClaims(account);
            syncStatusRecorder.recordClaimSyncCompleted(account.getId());   // 추적까지 끝난 회차만
        } catch (Exception e) {
            log.warn("Claim tracking failed (isolated): account={}", account.getId(), e);
            // lastClaimSyncAt 미갱신 → 다음 회차 창이 자동으로 넓어져 놓친 구간을 덮는다(D18).
            // 취소 보정과 달리 SUCCESS 를 깨지 않는다 — 추적은 이미 적재된 건의 상태 따라잡기다.
        }

        // 교환 적재는 백필 앞이다 — 새로 생긴 미연결 claim 을 같은 회차에 처리하려면 백필이 뒤여야 한다.
        try {
            claimSyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> a.syncExchanges(account));
        } catch (Exception e) {
            // 교환은 신규 연동이다 — 실패해도 주문·취소·반품(Stage A)을 되돌리지 않는다(PLAN §9).
            log.warn("Exchange claim sync failed (isolated): account={}", account.getId(), e);
        }

        try {
            claimOrderBackfillService.backfill(account);
        } catch (Exception e) {
            // 백필은 정확도 보정이다 — 실패해도 주문·취소는 정상이므로 SUCCESS 를 깨지 않는다
            // (취소 보정과 다른 판단: 취소된 라인은 구매 가능해 보이지만, 미연결 클레임은
            // 화면에 "주문 미연결" 로 이미 보인다).
            log.warn("Claim order backfill failed (isolated): account={}", account.getId(), e);
        }

        // 문의 적재는 클레임 단계 뒤다(2609_23 D12) — 별도 스케줄러·별도 버튼을 만들지 않고 여기 합류한다.
        try {
            inquirySyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> {
                        a.syncInquiries(account);
                        // 성공 회차에만 갱신한다. 엔티티에 직접 setter 를 쓰면 이 파사드는 트랜잭션
                        // 밖이라 조용히 유실된다 — 클레임과 같은 기록기를 지난다.
                        syncStatusRecorder.recordInquirySyncCompleted(account.getId());
                    });
        } catch (Exception e) {
            // 문의는 조회 기능이다 — 실패해도 주문·취소·클레임 결과를 되돌리지 않는다.
            // lastInquirySyncAt 미갱신 → 다음 회차 앵커가 자동으로 넓어져 놓친 구간을 덮는다.
            log.warn("Inquiry sync failed (isolated): account={}", account.getId(), e);
        }

        if (orderPartial != null) {
            syncStatusRecorder.recordPartial(account.getId(), orderPartial, false, true);
        } else {
            syncStatusRecorder.recordSuccess(account.getId());
        }
        return new OrderSyncResult(
                LocalDateTime.now(),
                orders.newCount(),
                orders.updatedCount(),
                cancels.matchedUpdated());
    }
}
