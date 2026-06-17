package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link OrderSyncFacade} 구현.
 *
 * syncOne = ordersheets(Phase2) → returnRequests 취소 보정(§A) 순서로 한 계정을 동기화한다.
 * syncEach 는 계정마다 try/catch 로 격리해 한 계정 실패가 전체를 롤백하지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
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
