package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.claim.ClaimOrderBackfillService;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link OrderSyncFacade} 구현.
 *
 * syncOne = ordersheets(Phase2) → returnRequests 취소 보정(§A) → 미완결 추적(05) → 교환 적재(06) →
 * 클레임 주문 백필(04) 순서로 한 계정을 동기화한다.
 * syncEach 는 계정마다 try/catch 로 격리해 한 계정 실패가 전체를 롤백하지 않게 한다.
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

    private static final String PLATFORM_COUPANG = "COUPANG";

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CoupangOrderSyncService coupangOrderSyncService;
    private final CoupangReturnSyncService coupangReturnSyncService;
    private final SyncStatusRecorder syncStatusRecorder;
    private final ClaimOrderBackfillService claimOrderBackfillService;
    /** 교환 클레임 동기화 어댑터(D21). 플랫폼 미지원(네이버)·빈 리스트(local/test)면 조용히 건너뛴다. */
    private final List<ClaimSyncAdapter> claimSyncAdapters;

    @Override
    public OrderSyncResult sync(Long accountId) {
        return sync(accountId, OrderSyncScope.FULL);
    }

    @Override
    public OrderSyncResult sync(Long accountId, OrderSyncScope scope) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        return syncOne(account, scope);   // 단건은 격리 없이 예외 전파
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
        if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
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
        OrderSyncResult total = OrderSyncResult.empty();
        for (MarketplaceAccount account : accounts) {
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                continue;
            }
            try {
                total = total.plus(syncOne(account, OrderSyncScope.FULL));
            } catch (Exception e) {
                log.warn("Order sync failed for account={}, isolated and continue", account.getId(), e);
            }
        }
        return total;
    }

    /**
     * 한 계정: ordersheets 먼저 → 취소 보정(이미 적재된 주문 위에 보정).
     *
     * {@code scope} 는 ordersheets 가 조회할 상태만 좁힌다 — 취소 보정과 상태 기록은 범위와 무관하게
     * 그대로 돈다(PLAN 2609_16 D5·D6).
     */
    private OrderSyncResult syncOne(MarketplaceAccount account, OrderSyncScope scope) {
        // Drive the tenant from the account being synced so saved order_item/shopping_list_item
        // (@TenantId) land in the account's tenant regardless of trigger (web admin or a future
        // batch/@Scheduled with no SecurityContext). Save/restore the previous value instead of
        // blindly clearing, so a web request's TenantContext survives across the account loop.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());

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
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
