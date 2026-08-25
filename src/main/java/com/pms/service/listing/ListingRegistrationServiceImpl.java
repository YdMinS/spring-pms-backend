package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaResolver;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.OptionCategoryMeta;
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
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;
    private final TagMergeService tagMergeService;
    private final MasterChannelConfigService masterChannelConfigService;
    private final CategoryMetaResolver categoryMetaResolver;

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

        // 42: the payload pushes only active options → refuse a register with an empty active subset (defensive:
        // setActiveOptions already enforces min 1 active, but the payload must never be empty).
        boolean anyActive = productListingOptionRepository.findByProductListingId(listingId).stream()
                .anyMatch(o -> Boolean.TRUE.equals(o.getActive()));
        if (!anyActive) {
            throw new IllegalArgumentException("활성 옵션 없음");
        }

        // 47: required category attributes must be filled on the master before push (empty schema = skip).
        assertRequiredCategoryAttributes(cell, acct);

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

    /**
     * MUST-KEEP (47/59): every required category attribute must have a non-blank value on each ACTIVE option
     * (master shared default {@code ++} per-option override) before the cell is pushed. Register targets a
     * single (master × channel) cell → one category → one {@code getMeta} call (schema is category-scoped, not
     * per-option). An empty schema (e.g. NAVER) skips it. Options unmatched to a master option (by name) fall
     * back to the master values only (no override).
     */
    private void assertRequiredCategoryAttributes(ProductListing cell, MarketplaceAccount acct) {
        String code = masterChannelConfigService.resolvePlatformCategoryCode(cell);
        CategoryMetaSchema schema = categoryMetaResolver.resolve(cell.getPlatform()).getMeta(acct, code);
        if (schema.attributes().isEmpty()) {
            return;   // empty schema = nothing required (harmless)
        }
        MasterProduct master = cell.getMasterProduct();
        Map<String, String> masterAttributes = master != null ? master.getCategoryAttributes() : null;
        Map<String, MasterProductOption> byName = master == null ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getName, o -> o, (a, b) -> a));

        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // only active options are pushed → only they need required values
            }
            MasterProductOption mo = byName.get(option.getOptionName());
            Map<String, String> values = OptionCategoryMeta.merge(
                    masterAttributes, mo != null ? mo.getCategoryAttributes() : null);
            for (CategoryAttribute attribute : schema.attributes()) {
                if (attribute.required()) {
                    String value = values.get(attribute.name());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                                "필수 카테고리 속성 누락: " + option.getOptionName() + " / " + attribute.name());
                    }
                }
            }
        }
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
