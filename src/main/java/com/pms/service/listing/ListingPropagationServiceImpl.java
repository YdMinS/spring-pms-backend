package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.PendingSyncResponse;
import com.pms.dto.response.PushSyncResponse;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Layer B: gated market push of pending cells (FEATURE_2608_06 / 3d). See {@link ListingPropagationService}.
 *
 * <p>{@link #pushSync} is a single {@code @Transactional} synchronous sequential batch (manual multi-select,
 * small volume). {@code adapter.update} returns immediately (no approval await), and a per-cell failure is a
 * pre-flush HTTP/account error caught before any save — so it isolates cleanly without poisoning the
 * transaction (the same reasoning as 3c {@code syncApprovals}). {@code @Async}/queue bulk = follow-up.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingPropagationServiceImpl implements ListingPropagationService {

    private static final Logger log = LoggerFactory.getLogger(ListingPropagationServiceImpl.class);

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;
    private final TagMergeService tagMergeService;

    @Override
    public List<PendingSyncResponse> pendingSync() {
        return productListingRepository.findByNeedsMarketSyncTrue().stream()
                .map(cell -> {
                    MasterProduct master = cell.getMasterProduct();
                    return PendingSyncResponse.builder()
                            .productListingId(cell.getId())
                            .masterProductName(master != null ? master.getName() : null)
                            .seller(cell.getSeller() != null ? cell.getSeller().getSellerName() : null)
                            .platform(cell.getPlatform())
                            .status(cell.getStatus().name())
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional
    public PushSyncResponse pushSync(List<Long> listingIds) {
        int requested = listingIds.size(), pushed = 0, skipped = 0, failed = 0;
        for (Long id : listingIds) {
            // findScopedById (@TenantId-filtered) → skip (not 404) a cross-tenant/absent id.
            ProductListing cell = productListingRepository.findScopedById(id).orElse(null);
            if (cell == null || cell.getPlatformProductId() == null || !cell.isNeedsMarketSync()) {
                skipped++;
                continue;   // not registered, or not pending → nothing to push
            }
            Optional<MarketplaceAccount> account = marketplaceAccountRepository
                    .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform());
            if (account.isEmpty() || Boolean.FALSE.equals(account.get().getIsActive())) {
                skipped++;
                continue;   // no account, or inactive
            }
            Optional<GeneratedProductData> gen = generatedProductDataRepository.findByProductListingId(id);
            if (gen.isEmpty()) {
                skipped++;
                continue;   // no generated assets to push
            }

            try {
                ListingChannel adapter = resolver.resolve(cell.getPlatform());
                adapter.update(cell, gen.get(), account.get());   // whole-object re-submit → PUT (active options only)
                // 42: options deactivated on this channel are dropped from the re-submitted payload → they are no
                // longer live on the market. Revert any previously-APPROVED deactivated option to NOT_APPROVED
                // (active options keep their approval). ⚠️ Orchestration owns this state change (@Transactional
                // boundary); the adapter only filters items[]. Coupang cannot physically delete an approved option
                // (stop-selling API = follow-up) — this step is payload-exclusion + local status only.
                for (ProductListingOption option : productListingOptionRepository.findByProductListingId(id)) {
                    if (!Boolean.TRUE.equals(option.getActive())
                            && option.getApprovalStatus() == OptionApprovalStatus.APPROVED) {
                        productListingOptionRepository.save(
                                option.toBuilder().approvalStatus(OptionApprovalStatus.NOT_APPROVED).build());
                    }
                }
                // Push succeeded → append the merged tag snapshot (33) if it changed. Failed cells never reach here.
                List<String> merged = tagMergeService.resolveTags(cell);
                tagMergeService.recordRevisionIfChanged(cell, merged);
                // Success: whole re-submit = re-review → SUBMITTED (no transition guard) + clear dirty marker.
                // Option approvalStatus is left untouched here — fetchStatus/syncApprovals (3c) re-detect it.
                productListingRepository.save(cell.toBuilder()
                        .status(ListingStatus.SUBMITTED)
                        .needsMarketSync(false)
                        .build());
                pushed++;
            } catch (Exception e) {
                failed++;   // isolate: one failed cell must not abort the batch
                log.warn("[LISTING-PUSH] listingId={} push failed: {}", id, e.getMessage());
            }
        }
        return PushSyncResponse.builder()
                .requested(requested).pushed(pushed).skipped(skipped).failed(failed).build();
    }
}
