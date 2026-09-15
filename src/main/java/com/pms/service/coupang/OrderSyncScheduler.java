package com.pms.service.coupang;

import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주문·클레임·문의 백그라운드 동기화 (FEATURE_2609_49 / D1·D2).
 *
 * <p>사용자 대기 경로에서 쿠팡 전량 조회를 뺀 자리다. 화면은 로컬 DB 를 즉시 그리고, 마켓 왕복은 여기서 돈다.
 *
 * <p>🔴 <b>주문 동기화의 유일한 스케줄 진입점이다.</b> 주기 작업을 추가할 일이 생기면 새 스케줄러를
 * 만들지 말고 여기 합류한다 — 여러 스케줄러가 같은 채널을 동시에 치면 락에 막혀 서로를 건너뛴다.
 *
 * <p>⚠️ 스케줄 실행은 SecurityContext 도 TenantContext 도 없다 — 일반 finder 는 NO_TENANT 로 0건이므로
 * 테넌트를 명시 순회한다({@link com.pms.service.settlement.SettlementSyncScheduler} 와 같은 자세).
 *
 * <p>⚠️ {@code @Scheduled} 는 <b>인스턴스 내부</b>다. 단일 인스턴스 전제이며, 다중화하면 이 작업이
 * 인스턴스 수만큼 중복 실행된다(적재는 멱등이지만 마켓 호출이 배수가 된다). {@link AccountSyncLock}·
 * {@code CoupangCallBudget} 이 이미 같은 한계를 안고 있어 분산 제한은 세트로 따라온다.
 *
 * <p>🔴 <b>락은 여기서 만지지 않는다</b>(D8). 수동 클릭과 겹치면 {@code syncOne} 이 이미 건너뛴다 —
 * 스케줄 쪽에 조정 로직을 만들면 판단이 두 벌이 된다.
 *
 * <p>🔴 <b>모든 cron 에 {@code zone} 을 명시한다</b>(D11). 서버 컨테이너에 TZ 설정이 없어 JVM 기본이
 * UTC 다 — zone 을 빼면 "새벽 3시" 가 정오 12시(KST)에 돈다. 시각을 KST 로 읽고 쓰는 화면·창 계산
 * ({@link SyncWindow#KST})과 같은 기준으로 맞춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncScheduler {

    private final OrderSyncFacade orderSyncFacade;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    /** 15분 티어 = 활성 주문 + 취소 보정 + 반품/교환 + 문의(D2). 계정당 6~7왕복. */
    @Scheduled(cron = "${oclyx.order-sync.quick-cron:0 0/15 * * * *}", zone = "Asia/Seoul")
    public void syncQuick() {
        runForEachTenant(OrderSyncPreset.QUICK);
    }

    /**
     * 전량 리컨실 = 새벽 3시 KST(D1).
     *
     * <p>🔴 정산 매출 동기화(04:30 KST, {@code oclyx.settlement.revenue-sync-cron})보다 <b>먼저</b> 끝나야
     * 정산 대사가 최신 주문을 본다. 시각을 옮길 때 그 순서를 깨지 말 것 — 그리고 두 스케줄러가
     * <b>같은 zone</b> 을 써야 그 순서가 성립한다.
     */
    @Scheduled(cron = "${oclyx.order-sync.full-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void syncReconcile() {
        runForEachTenant(OrderSyncPreset.RECONCILE);
    }

    private void runForEachTenant(OrderSyncPreset preset) {
        for (Long tenantId : marketplaceAccountRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                OrderSyncResult result = orderSyncFacade.syncAll(preset);
                log.info("Scheduled order sync: preset={} tenant={} new={} updated={} canceled={} skipped={}",
                        preset, tenantId, result.newOrders(), result.updatedOrders(),
                        result.canceledUpdated(), result.skippedAccounts());
            } catch (Exception e) {
                // 한 테넌트의 실패가 나머지를 멈추면 안 된다. 계정 단위 격리는 파사드가 이미 한다.
                log.warn("Scheduled order sync failed: preset={} tenant={}", preset, tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
