package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SyncStatus;
import com.pms.dto.response.SyncTargetResponse;
import com.pms.repository.MarketplaceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link SyncTargetService} implementation.
 *
 * The target filter is the <b>same rule</b> as {@link OrderSyncFacadeImpl}'s manual syncEach (active + COUPANG or
 * ELEVENST, FEATURE_2610_17 / D9) — an account not listed here is not synced either, so the progress denominator
 * matches reality.
 *
 * The mapping copies every field except credentials (see the {@link SyncTargetResponse} comment).
 * Both finders fetch the seller eagerly with {@code @EntityGraph}, so there is no LazyInit risk.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncTargetServiceImpl implements SyncTargetService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Override
    public List<SyncTargetResponse> list(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId == null)
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        return accounts.stream()
                .filter(account -> Platform.COUPANG.equals(account.getPlatform())
                        || Platform.ELEVENST.equals(account.getPlatform()))
                .map(this::toResponse)
                .toList();
    }

    private SyncTargetResponse toResponse(MarketplaceAccount account) {
        SyncStatus status = account.getLastSyncStatus();
        return SyncTargetResponse.builder()
                .accountId(account.getId())
                .sellerId(account.getSeller() == null ? null : account.getSeller().getId())
                .sellerName(account.getSeller() == null ? null : account.getSeller().getSellerName())
                .platform(account.getPlatform().name())
                .accountAlias(account.getAccountAlias())
                .lastSyncStatus(status == null ? null : status.name())
                .lastSyncAt(account.getLastSyncAt())
                .lastOrderSyncAt(account.getLastOrderSyncAt())
                .lastCancelSyncAt(account.getLastCancelSyncAt())
                .lastClaimSyncAt(account.getLastClaimSyncAt())
                .lastSyncError(account.getLastSyncError())
                .build();
    }
}
