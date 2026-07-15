package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link OrderSyncFacade} 구현.
 *
 * syncOne = ordersheets(Phase2) → returnRequests 취소 보정(§A) 순서로 한 계정을 동기화한다.
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

    @Override
    public OrderSyncResult sync(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        return syncOne(account);   // 단건은 격리 없이 예외 전파
    }

    @Override
    public OrderSyncResult syncBySeller(Long sellerId) {
        return syncEach(marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId));
    }

    @Override
    public OrderSyncResult syncAll() {
        return syncEach(marketplaceAccountRepository.findByIsActiveTrue());
    }

    /** 계정 목록을 격리 동기화해 합산 (COUPANG 만, 한 계정 실패는 로그 후 계속). */
    private OrderSyncResult syncEach(List<MarketplaceAccount> accounts) {
        OrderSyncResult total = OrderSyncResult.empty();
        for (MarketplaceAccount account : accounts) {
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                continue;
            }
            try {
                total = total.plus(syncOne(account));
            } catch (Exception e) {
                log.warn("Order sync failed for account={}, isolated and continue", account.getId(), e);
            }
        }
        return total;
    }

    /** 한 계정: ordersheets 먼저 → 취소 보정(이미 적재된 주문 위에 보정). */
    private OrderSyncResult syncOne(MarketplaceAccount account) {
        SyncResult orders = coupangOrderSyncService.syncAccount(account);
        CancelSyncResult cancels = coupangReturnSyncService.syncCancels(account);
        return new OrderSyncResult(
                LocalDateTime.now(),
                orders.newCount(),
                orders.updatedCount(),
                cancels.matchedUpdated());
    }
}
