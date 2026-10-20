package com.pms.service.claim;

import com.pms.domain.MarketplaceAccount;
import com.pms.dto.response.ClaimSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.AccountSyncLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link ClaimSyncService} implementation (FEATURE_2609_70 / D14 · D15 · D15-1) — the claims-only round of each
 * marketplace is its {@link ClaimSyncAdapter#syncClaimsOnly} (FEATURE_2610_23 / D12 · D21).
 *
 * <p>⚠️ No {@code @Transactional} — an external HTTP loop (the same stance as {@code OrderSyncFacadeImpl} ·
 * {@code InquirySyncServiceImpl}). DB writes happen only inside the existing upserters' {@code REQUIRES_NEW}.
 *
 * <p>🔴 <b>It takes the lock</b> — the one point where it parts from the inquiry path. Inquiries write only their own
 * tables, but claims write <b>the same tables</b> as the order sync, so splitting or dropping the key would bring back
 * the double load 2609_48 blocked. The key is therefore the order's {@code (account, ORDER)}.
 *
 * <p>🔴 A failure of the round propagates (D15-1) — see {@link ClaimSyncAdapter#syncClaimsOnly}. Per-channel failure
 * isolation is the screen's job (the same contract as the inquiry precedent). {@code last_claim_sync_at} is not
 * stamped on a failure either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimSyncServiceImpl implements ClaimSyncService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final AccountSyncLock accountSyncLock;
    private final List<ClaimSyncAdapter> claimSyncAdapters;

    @Override
    public ClaimSyncResponse sync(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));

        // FEATURE_2610_23 / D12 · D14: a marketplace without a claim adapter keeps the "unsupported channel" 400.
        ClaimSyncAdapter adapter = claimSyncAdapters.stream()
                .filter(a -> a.platform().equals(account.getPlatform()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "클레임 조회를 지원하지 않는 채널입니다: " + account.getPlatform()));

        AccountSyncLock.Lease lease =
                accountSyncLock.tryAcquire(AccountSyncLock.SyncWork.ORDER, account.getId());
        if (lease == null) {
            // 🔴 Returns at once without calling the marketplace (Coupang · 11st). The recorder is not called
            // either — a skipped round is neither success nor failure, and recording it would push the "last
            // sync" time to a moment nothing was read.
            log.info("Claim sync skipped (already running): account={} heldSec={}",
                    account.getId(),
                    accountSyncLock.heldSeconds(AccountSyncLock.SyncWork.ORDER, account.getId()));
            return new ClaimSyncResponse(account.getId(), true, LocalDateTime.now());
        }

        // try-with-resources 라 본문이 예외를 던져도 빠져나가기 전에 풀린다 —
        // "실패했으니 곧바로 다시 누른다" 가 성립해야 한다.
        try (lease) {
            return syncLocked(account, adapter);
        }
    }

    /** The body under the lock — the adapter's claims-only round under the account's tenant. */
    private ClaimSyncResponse syncLocked(MarketplaceAccount account, ClaimSyncAdapter adapter) {
        // 저장되는 클레임이 이 계정의 테넌트로 들어가게 한다(문의 경로와 같은 이유·같은 방식).
        // 웹 요청은 이미 토큰의 테넌트가 세팅돼 있으므로 이전 값을 저장해 두었다가 되돌린다 —
        // clear() 로 통일하면 직후 목록 조회가 빈다.
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(account.getTenantId());
            adapter.syncClaimsOnly(account);
            return new ClaimSyncResponse(account.getId(), false, LocalDateTime.now());
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
