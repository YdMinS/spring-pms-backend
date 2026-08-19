package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Channel registration orchestration (FEATURE_2608_06 / 3c). See {@link ListingRegistrationService}.
 *
 * <p>Each write method is {@code @Transactional} (DB save atomicity); the single HTTP call runs inside the
 * transaction once, with no approval wait. {@code syncApprovals} reuses {@link #fetchStatus} directly (same
 * code path) — the inner call is a self-invocation, so its {@code @Transactional} is intentionally not a new
 * boundary: the sweep is one transaction and a caught per-listing failure (pre-flush: account/HTTP error)
 * does not poison it, so successful promotions persist.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingRegistrationServiceImpl implements ListingRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ListingRegistrationServiceImpl.class);

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;
    private final TagMergeService tagMergeService;

    @Override
    @Transactional
    public ListingRegisterResponse register(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (cell.getStatus() != ListingStatus.DRAFT) {
            throw new IllegalArgumentException("이미 등록됨");          // idempotency guard (400)
        }
        GeneratedProductData gen = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new IllegalArgumentException("자동생성 먼저"));  // 03 regenerate first (400)

        MarketplaceAccount acct = resolveAccount(cell);
        ListingChannel adapter = resolver.resolve(cell.getPlatform());

        String sellerProductId = adapter.register(cell, gen, acct);

        // Push succeeded → append the merged tag snapshot (33) if it changed. Failed cells never reach here.
        List<String> merged = tagMergeService.resolveTags(cell);
        tagMergeService.recordRevisionIfChanged(cell, merged);

        // Options keep NOT_APPROVED (not yet approved). Immutable entity → toBuilder + save.
        productListingRepository.save(cell.toBuilder()
                .platformProductId(sellerProductId)
                .status(ListingStatus.SUBMITTED)
                .build());

        return ListingRegisterResponse.builder()
                .productListingId(cell.getId())
                .status(ListingStatus.SUBMITTED.name())
                .platformProductId(sellerProductId)
                .build();
    }

    @Override
    @Transactional
    public ListingStatusResponse fetchStatus(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (cell.getPlatformProductId() == null) {
            throw new IllegalArgumentException("미등록");               // DRAFT, not pushed yet (400)
        }

        MarketplaceAccount acct = resolveAccount(cell);
        ListingChannel adapter = resolver.resolve(cell.getPlatform());
        FetchResult result = adapter.fetchStatus(cell, acct);

        productListingRepository.save(cell.toBuilder().status(result.status()).build());

        // On SELLING, sync matched options (by option name) → market ids + APPROVED. Unmatched options keep
        // NOT_APPROVED (부분승인완료: some options may still be pending — option truth is approvalStatus).
        Map<String, FetchResult.OptionId> byName = result.status() == ListingStatus.SELLING
                ? result.options().stream()
                        .filter(o -> o.optionName() != null)
                        .collect(Collectors.toMap(FetchResult.OptionId::optionName, o -> o, (a, b) -> a))
                : Map.of();

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        List<ListingStatusResponse.OptionStatus> optionStatuses = new ArrayList<>();
        for (ProductListingOption option : options) {
            FetchResult.OptionId match = byName.get(option.getOptionName());
            if (match != null) {
                ProductListingOption updated = option.toBuilder()
                        .platformOptionId(match.vendorItemId())
                        .sellerProductItemId(match.sellerProductItemId())
                        .approvalStatus(OptionApprovalStatus.APPROVED)
                        .build();
                productListingOptionRepository.save(updated);
                optionStatuses.add(ListingStatusResponse.OptionStatus.from(updated));
            } else {
                optionStatuses.add(ListingStatusResponse.OptionStatus.from(option));
            }
        }

        return ListingStatusResponse.builder()
                .productListingId(cell.getId())
                .status(result.status().name())
                .options(optionStatuses)
                .build();
    }

    @Override
    @Transactional
    public ListingSyncResponse syncApprovals() {
        List<ProductListing> pending = productListingRepository.findPendingApproval();
        int swept = 0, promoted = 0, stillPending = 0, failed = 0;
        for (ProductListing cell : pending) {
            swept++;
            try {
                // Reuse fetchStatus (same code path). Self-invocation: no new tx boundary — see class doc.
                ListingStatusResponse result = fetchStatus(cell.getId());
                if (ListingStatus.SELLING.name().equals(result.getStatus())) {
                    promoted++;
                } else {
                    stillPending++;
                }
            } catch (Exception e) {
                failed++;   // isolate: one failure must not abort the whole sweep
                log.warn("[LISTING-SYNC] listingId={} sync failed: {}", cell.getId(), e.getMessage());
            }
        }
        return ListingSyncResponse.builder()
                .swept(swept)
                .promotedToSelling(promoted)
                .stillPending(stillPending)
                .failed(failed)
                .build();
    }

    /** Resolve the (seller, platform) marketplace account for a cell (404 if none, 400 if inactive). */
    private MarketplaceAccount resolveAccount(ProductListing cell) {
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
        if (Boolean.FALSE.equals(acct.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        return acct;
    }
}
