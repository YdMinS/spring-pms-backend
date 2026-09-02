package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SyncStatus;
import com.pms.repository.MarketplaceAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 채널 동기화 결과의 실제 쓰기 (FEATURE_2609_02 / PLAN D7).
 *
 * ⚠️ 모든 메서드가 {@code REQUIRES_NEW} 다. 동기화 트랜잭션에 합류(REQUIRED)하면 그 트랜잭션이
 * 롤백될 때 "실패했다"는 기록까지 함께 사라져, 실패한 채널이 영원히 무기록으로 남는다.
 *
 * ⚠️ 이 클래스는 {@link SyncStatusRecorder} 를 통해서만 호출한다 — 기록 실패를 삼키는 try/catch 는
 * 트랜잭션 프록시 <b>바깥</b>에 있어야 하기 때문이다(그 이유는 Recorder 주석 참고).
 *
 * 계정 조회는 {@code findById} — {@code @TenantId} 가 자동 스코프하므로 호출 시점에 TenantContext 가
 * 세팅돼 있어야 한다(OrderSyncFacadeImpl.syncOne 이 보장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class SyncStatusWriter {

    private final MarketplaceAccountRepository marketplaceAccountRepository;

    /** 전 상태 성공 + 취소 보정 성공 — 세 시각을 모두 now 로, 사유는 지운다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void writeSuccess(Long accountId) {
        find(accountId).ifPresent(account -> {
            LocalDateTime now = LocalDateTime.now();
            marketplaceAccountRepository.save(account.toBuilder()
                    .lastSyncStatus(SyncStatus.SUCCESS)
                    .lastSyncAt(now)
                    .lastOrderSyncAt(now)
                    .lastCancelSyncAt(now)
                    .lastSyncError(null)
                    .build());
        });
    }

    /**
     * 부분 성공(D18). 완료된 단계의 시각만 갱신한다 — 완료되지 않은 단계는 "마지막 성공 시각"을 흐리지
     * 않도록 미변경으로 둔다. reason 문구는 호출자(파사드)가 확정한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void writePartial(Long accountId, String reason, boolean orderDone, boolean cancelDone) {
        find(accountId).ifPresent(account -> {
            LocalDateTime now = LocalDateTime.now();
            marketplaceAccountRepository.save(account.toBuilder()
                    .lastSyncStatus(SyncStatus.PARTIAL)
                    .lastSyncAt(now)
                    .lastOrderSyncAt(orderDone ? now : account.getLastOrderSyncAt())
                    .lastCancelSyncAt(cancelDone ? now : account.getLastCancelSyncAt())
                    .lastSyncError(reason)
                    .build());
        });
    }

    /** 주문 조회 단계 전체 실패 — 두 "마지막 성공" 시각은 미변경. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void writeFailure(Long accountId, String summary) {
        find(accountId).ifPresent(account ->
                marketplaceAccountRepository.save(account.toBuilder()
                        .lastSyncStatus(SyncStatus.FAILED)
                        .lastSyncAt(LocalDateTime.now())
                        .lastSyncError(summary)
                        .build()));
    }

    /** 기록 대상이 사라진 것이 동기화를 깨면 안 된다 — 예외 대신 로그만 남기고 비운다. */
    private Optional<MarketplaceAccount> find(Long accountId) {
        Optional<MarketplaceAccount> account = marketplaceAccountRepository.findById(accountId);
        if (account.isEmpty()) {
            log.warn("Sync status record skipped: account={} not found", accountId);
        }
        return account;
    }
}
