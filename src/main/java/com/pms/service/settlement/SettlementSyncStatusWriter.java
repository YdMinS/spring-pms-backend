package com.pms.service.settlement;

import com.pms.repository.MarketplaceAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 정산 동기화 앵커({@code last_settlement_sync_at}) 쓰기 (FEATURE_2609_30 / PLAN D11).
 *
 * <p>⚠️ {@code REQUIRES_NEW} — 적재는 페이지 단위 트랜잭션이라 여기서 합류할 트랜잭션이 없고,
 * 있더라도 기록이 함께 롤백되면 안 된다({@code SyncStatusWriter} 와 같은 이유).
 *
 * <p>🔴 <b>전 페이지가 끝난 회차에만 호출한다.</b> 이 값의 의미는 "마지막으로 시도한" 이 아니라
 * <b>"마지막으로 끝까지 성공한"</b> 이다({@code MarketplaceAccount:87-89} 관례). 부분 실패에 갱신하면
 * 놓친 구간을 다음 회차가 덮지 못하고, 수동 갱신 최소 간격 가드까지 걸려 재시도가 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSyncStatusWriter {

    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRevenueSyncAt(Long accountId) {
        marketplaceAccountRepository.findById(accountId).ifPresentOrElse(
                account -> marketplaceAccountRepository.save(account.toBuilder()
                        .lastSettlementSyncAt(LocalDateTime.now())
                        .build()),
                // 기록 대상이 사라진 것이 적재를 깨면 안 된다 — 예외 대신 로그만.
                () -> log.warn("Settlement sync anchor skipped: account={} not found", accountId));
    }
}
