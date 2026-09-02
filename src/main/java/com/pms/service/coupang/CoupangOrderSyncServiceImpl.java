package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    // 전체 상태를 상태별로 조회한다. status 는 단일값 파라미터라, 한 상태만 조회하면 주문이
    // 다음 단계로 넘어갔을 때(예: ACCEPT→INSTRUCT) 그 필터에 안 잡혀 status 갱신이 누락된다.
    // 모든 상태를 돌면 박스 누락 없이 현재 상태가 항상 최신으로 반영된다.
    private static final List<CoupangOrderStatus> SYNC_STATUSES = List.of(CoupangOrderStatus.values());

    private final CoupangOrderStatusSyncer statusSyncer;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

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
        int newCount = 0;
        int updatedCount = 0;
        int pages = 0;
        List<CoupangOrderStatus> failedStatuses = new ArrayList<>();

        // 상태 순서는 enum 순서(라이프사이클) 유지 — ACCEPT → INSTRUCT 가 먼저 커밋되는 게 발송처리에 유리.
        for (CoupangOrderStatus status : SYNC_STATUSES) {
            try {
                StatusSyncResult r = statusSyncer.syncStatus(account, status);
                newCount += r.newCount();
                updatedCount += r.updatedCount();
                pages += r.pages();
            } catch (Exception e) {
                // 이미 커밋된 앞 상태는 살아남는다. 남은 상태도 계속 시도한다(부분 성공 > 전량 실패).
                failedStatuses.add(status);
                log.warn("Coupang status sync failed (committed statuses kept): account={} status={}",
                        account.getId(), status, e);
            }
        }

        // 전 상태 실패 = 계정 자체 문제(자격증명·네트워크) → 기존처럼 예외로 파사드 격리에 맡긴다.
        if (failedStatuses.size() == SYNC_STATUSES.size()) {
            throw new IllegalStateException("쿠팡 주문 동기화 전 상태 실패: account=" + account.getId());
        }

        log.info("Coupang sync done: account={} statuses={} pages={} new={} updated={} failed={}",
                account.getId(), SYNC_STATUSES, pages, newCount, updatedCount, failedStatuses);
        return new SyncResult(newCount, updatedCount, pages, List.copyOf(failedStatuses));
    }
}
