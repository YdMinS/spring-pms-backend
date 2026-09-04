package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@link CoupangOrderSyncService} 구현 — 상태별 동기화 오케스트레이션.
 *
 * 조회·upsert 자체는 {@link CoupangOrderStatusSyncer#syncStatus} 가 상태 1개씩 수행하고, 그 메서드가
 * 커밋 단위다. 여기서는 상태를 돌며 실패를 격리하고 결과를 합산한다.
 *
 * ⚠️ 이 클래스에 {@code @Transactional} 을 붙이면 안 된다(PLAN D15). 붙이면 syncer 가 REQUIRED 로
 * 그 트랜잭션에 합류해 커밋 경계가 다시 하나로 합쳐지고, 뒤쪽 상태의 쿠팡 실패(예: 504)가 앞쪽 상태의
 * upsert 까지 롤백시킨다 — 그 계정 주문이 order_item 에 한 건도 남지 않아 발송처리가 전량 미매칭된다
 * (2026-09-02 사고). 회귀 테스트: {@code OrderSyncCommitBoundaryTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangOrderSyncServiceImpl implements CoupangOrderSyncService {

    private static final String PLATFORM_COUPANG = "COUPANG";

    private final CoupangOrderStatusSyncer statusSyncer;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public SyncResult syncAll() {
        SyncResult total = SyncResult.empty();
        for (MarketplaceAccount account : marketplaceAccountRepository.findByIsActiveTrue()) {
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                continue;
            }
            total = total.plus(syncAccount(account));
        }
        return total;
    }

    @Override
    public SyncResult syncAccount(MarketplaceAccount account) {
        return syncAccount(account, OrderSyncScope.FULL);
    }

    @Override
    public SyncResult syncAccount(MarketplaceAccount account, OrderSyncScope scope) {
        // 정기 동기화 = 상태 부류별로 창이 다르다(PLAN 2609_14 D1). 창 계산은 SyncWindow 안에서만 한다(D8).
        // 범위(2609_16)는 창을 건드리지 않는다 — 도는 상태 수만 줄인다(D8).
        SyncWindow active = SyncWindow.recent(coupangProperties.getSyncDays());
        SyncWindow terminal = SyncWindow.recentSince(account.getLastOrderSyncAt(),
                coupangProperties.getTerminalSyncMinDays(), coupangProperties.getSyncDays());
        return sync(account, scope, status -> status.isTerminal() ? terminal : active);
    }

    @Override
    public SyncResult syncAccount(MarketplaceAccount account, SyncWindow window) {
        // 기간 백필 = 사용자가 지정한 창을 전 상태에 그대로 쓴다(2609_14 D7). 축소도 범위 축약도 끼얹지
        // 않는다(2609_16 D3) — 요청한 기간의 주문을 상태 불문 전부 가져오는 게 이 경로의 계약이다.
        return sync(account, OrderSyncScope.FULL, status -> window);
    }

    private SyncResult sync(MarketplaceAccount account, OrderSyncScope scope,
                            Function<CoupangOrderStatus, SyncWindow> windowFor) {
        // status 는 단일값 파라미터다 — 한 상태만 조회하면 주문이 다음 단계로 넘어갔을 때(예: ACCEPT→INSTRUCT)
        // 그 필터에 안 잡혀 status 갱신이 누락된다. 그래서 상태별로 돈다. 어디까지 도는지는 scope 가 정한다:
        // FULL 은 전 상태(박스 누락 없이 현재 상태가 항상 최신), ACTIVE 는 활성 상태만(2609_16 D1).
        List<CoupangOrderStatus> syncStatuses = scope.statuses();
        int newCount = 0;
        int updatedCount = 0;
        int pages = 0;
        List<CoupangOrderStatus> failedStatuses = new ArrayList<>();

        // 상태 순서는 enum 순서(라이프사이클) 유지 — ACCEPT → INSTRUCT 가 먼저 커밋되는 게 발송처리에 유리.
        for (CoupangOrderStatus status : syncStatuses) {
            SyncWindow w = windowFor.apply(status);   // 이 루프의 유일한 창 — 로그도 전부 이 값을 쓴다
            try {
                long startedAt = System.currentTimeMillis();
                StatusSyncResult r = statusSyncer.syncStatus(account, status, w);
                // 상태별 비용 계측(PLAN 2609_14 D9) — 어느 상태가 무거운지가 이후 튜닝(2609_16)의 유일한 근거다.
                log.info("Coupang status sync: account={} status={} window={} pages={} new={} updated={} elapsedMs={}",
                        account.getId(), status, w, r.pages(), r.newCount(), r.updatedCount(),
                        System.currentTimeMillis() - startedAt);
                newCount += r.newCount();
                updatedCount += r.updatedCount();
                pages += r.pages();
            } catch (Exception e) {
                // 이미 커밋된 앞 상태는 살아남는다. 남은 상태도 계속 시도한다(부분 성공 > 전량 실패).
                failedStatuses.add(status);
                log.warn("Coupang status sync failed (committed statuses kept): account={} status={} window={}",
                        account.getId(), status, w, e);
            }
        }

        // 전 상태 실패 = 계정 자체 문제(자격증명·네트워크) → 기존처럼 예외로 파사드 격리에 맡긴다.
        // 판정 기준은 "그 범위가 조회한 상태 수" 다(2609_16 D7) — ACTIVE(2개)를 6과 비교하면
        // 두 상태가 다 죽어도 예외가 안 나 계정 장애가 부분 실패로 위장된다.
        if (failedStatuses.size() == syncStatuses.size()) {
            throw new IllegalStateException("쿠팡 주문 동기화 전 상태 실패: account=" + account.getId());
        }

        // 창이 상태 부류별로 다르므로 집계 로그에는 창을 싣지 않는다 — 상태별 창은 위 계측 로그가 찍는다.
        log.info("Coupang sync done: account={} statuses={} pages={} new={} updated={} failed={}",
                account.getId(), syncStatuses, pages, newCount, updatedCount, failedStatuses);
        return new SyncResult(newCount, updatedCount, pages, List.copyOf(failedStatuses));
    }
}
