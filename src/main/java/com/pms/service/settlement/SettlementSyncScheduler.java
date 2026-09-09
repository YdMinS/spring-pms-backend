package com.pms.service.settlement;

import com.pms.dto.response.SettlementSyncResponse;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매출내역 일 1회 delta 적재 (FEATURE_2609_30 / PLAN D11).
 *
 * <p>🔴 <b>cron 은 프로퍼티다</b>({@code oclyx.settlement.revenue-sync-cron}). dev 에서 5분 주기 cron
 * 으로 낮춰 스케줄이 실제로 도는 것을 확인하고 prod 는 일 1회를 유지한다(사용자 요청 항목).
 *
 * <p>⚠️ 스케줄 실행은 SecurityContext 도 TenantContext 도 없다 — 일반 finder 는 NO_TENANT 로 0건이므로
 * 테넌트를 명시 순회한다(backend CLAUDE.md §9). 계정 단위 tenant 세팅은
 * {@code SettlementSyncServiceImpl.runAccount} 가 한 번 더 한다(웹·배치 공통 경로).
 *
 * <p>⚠️ {@code @Scheduled} 는 <b>인스턴스 내부</b>다. 현재 단일 인스턴스라 분산 락을 넣지 않았으므로,
 * <b>애플리케이션을 다중화하면 이 작업이 인스턴스 수만큼 중복 실행된다</b>. 적재 자체는 멱등이라
 * 데이터가 깨지지는 않지만 마켓 호출이 배수가 된다 — 다중화 시점에 분산 락이 필요하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSyncScheduler {

    private final SettlementSyncService settlementSyncService;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Scheduled(cron = "${oclyx.settlement.revenue-sync-cron:0 30 4 * * *}")
    public void syncRevenueDaily() {
        List<Long> tenantIds = marketplaceAccountRepository.findDistinctTenantIds();
        for (Long tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId);
                SettlementSyncResponse result = settlementSyncService.sync(null, null);
                log.info("Scheduled settlement revenue sync: tenant={} accounts={} lines={} matched={} "
                                + "unmatched={} duplicates={} failed={}",
                        tenantId, result.accounts(), result.lines(), result.matched(),
                        result.unmatched(), result.duplicates(), result.failedAccounts());
            } catch (Exception e) {
                // 한 테넌트의 실패가 나머지를 멈추면 안 된다. 계정 단위 격리는 서비스가 이미 한다.
                log.warn("Scheduled settlement revenue sync failed: tenant={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
