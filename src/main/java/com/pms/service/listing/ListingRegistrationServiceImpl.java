package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.dto.response.MarketOptionResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Channel registration orchestration (FEATURE_2608_06 / 3c). See {@link ListingRegistrationService}.
 *
 * <p>Each write method is {@code @Transactional} (DB save atomicity); the single HTTP call runs inside the
 * transaction once, with no approval wait. {@code syncApprovals} reuses the private refresh core (same
 * code path) — the inner call is a self-invocation, so its {@code @Transactional} is intentionally not a new
 * boundary: the sweep is one transaction and a caught per-listing failure (pre-flush: account/HTTP error)
 * does not poison it, so successful promotions persist.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingRegistrationServiceImpl implements ListingRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ListingRegistrationServiceImpl.class);

    /** Coupang answers 400 with this text while it is still processing a registration or an edit. */
    private static final String COUPANG_PROCESSING_MARKER = "등록 또는 수정되고 있습니다";
    private static final Pattern COUPANG_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

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

        // 42: the payload pushes only active options → refuse a register with an empty active subset (defensive:
        // setActiveOptions already enforces min 1 active, but the payload must never be empty).
        boolean anyActive = productListingOptionRepository.findByProductListingId(listingId).stream()
                .anyMatch(o -> Boolean.TRUE.equals(o.getActive()));
        if (!anyActive) {
            throw new IllegalArgumentException("활성 옵션 없음");
        }

        // 63: channel-owned registration policy (Coupang: required-attribute check for SINGLE / skip for AB).
        // The orchestration stays channel-vocabulary-free — it just delegates.
        adapter.validateRegistrable(cell, gen, acct);

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
    public ListingRegisterResponse updateRequest(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (cell.getPlatformProductId() == null) {
            throw new IllegalArgumentException("미등록");               // DRAFT → [마켓 등록] first (400)
        }
        MarketplaceAccount acct = resolveAccount(cell);
        GeneratedProductData gen = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new IllegalArgumentException("자동생성 먼저"));  // 03 regenerate first (400)

        // 42: the payload pushes only active options → an empty active subset would push an empty items[].
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        if (options.stream().noneMatch(o -> Boolean.TRUE.equals(o.getActive()))) {
            throw new IllegalArgumentException("활성 옵션 없음");
        }

        // 108/D4: no validateRegistrable — this mirrors pushSync (which does not call it) and the cell already
        // passed it at register time. Unlike pushSync the needsMarketSync dirty marker is ignored on purpose.
        ListingChannel adapter = resolver.resolve(cell.getPlatform());
        adapter.update(cell, gen, acct);

        // 42 (same rule as pushSync): options deactivated on this channel are dropped from the re-submitted
        // payload → they are no longer live on the market, so revert a previously-APPROVED one to NOT_APPROVED.
        for (ProductListingOption option : options) {
            if (!Boolean.TRUE.equals(option.getActive())
                    && option.getApprovalStatus() == OptionApprovalStatus.APPROVED) {
                productListingOptionRepository.save(
                        option.toBuilder().approvalStatus(OptionApprovalStatus.NOT_APPROVED).build());
            }
        }
        // Push succeeded → append the merged tag snapshot (33) if it changed. Failed cells never reach here.
        List<String> merged = tagMergeService.resolveTags(cell);
        tagMergeService.recordRevisionIfChanged(cell, merged);
        // Whole re-submit = re-review → SUBMITTED from any status (no transition guard) + clear dirty marker.
        productListingRepository.save(cell.toBuilder()
                .status(ListingStatus.SUBMITTED)
                .needsMarketSync(false)
                .build());

        return ListingRegisterResponse.builder()
                .productListingId(cell.getId())
                .status(ListingStatus.SUBMITTED.name())
                .platformProductId(cell.getPlatformProductId())
                .build();
    }

    @Override
    @Transactional
    public ListingStatusResponse fetchStatus(Long listingId) {
        Refreshed refreshed = refresh(listingId);

        // 2609_74/D9·D19: the reason is looked up on demand and never stored. A failed lookup must not
        // fail the refresh — the status was already saved above.
        ReviewNote note;
        try {
            note = refreshed.adapter().fetchReviewNote(
                    refreshed.cell(), refreshed.account(), refreshed.result().statusName());
        } catch (Exception e) {
            log.warn("[LISTING-REVIEW-NOTE] listingId={} lookup failed: {}", listingId, e.getMessage());
            note = ReviewNote.failed();
        }

        return ListingStatusResponse.builder()
                .productListingId(refreshed.cell().getId())
                .status(refreshed.result().status().name())
                .options(refreshed.options())
                .reviewNote(note == null ? null : note.text())
                .reviewNoteState(note == null ? null : note.state().name())
                .build();
    }

    /**
     * The product is still being processed on the market right after a registration or an edit: a "try again
     * shortly" answer, not a server fault. Returns a 409 carrying Coupang's own wording so the screen shows it
     * instead of "Internal server error" (and the global handler logs one WARN line, no stack trace).
     * Any other 400 is returned unchanged.
     */
    private static RuntimeException marketBusyOrSame(HttpClientErrorException.BadRequest e) {
        String body = e.getResponseBodyAsString();
        if (!body.contains(COUPANG_PROCESSING_MARKER)) {
            return e;
        }
        Matcher matcher = COUPANG_MESSAGE.matcher(body);
        return new BusinessException(matcher.find() ? matcher.group(1) : body, HttpStatus.CONFLICT);
    }

    private Refreshed refresh(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (cell.getPlatformProductId() == null) {
            throw new IllegalArgumentException("미등록");               // DRAFT, not pushed yet (400)
        }

        MarketplaceAccount acct = resolveAccount(cell);
        ListingChannel adapter = resolver.resolve(cell.getPlatform());
        FetchResult result;
        try {
            result = adapter.fetchStatus(cell, acct);
        } catch (HttpClientErrorException.BadRequest e) {
            throw marketBusyOrSame(e);
        }

        productListingRepository.save(cell.toBuilder().status(result.status()).build());

        // On SELLING, sync matched options → market ids + APPROVED. Unmatched options keep NOT_APPROVED
        // (부분승인완료: some options may still be pending — option truth is approvalStatus).
        boolean selling = result.status() == ListingStatus.SELLING;
        // 2609_22/D21: an option that already carries a vendorItemId is matched by THAT id — the name is only
        // the axis for the very first fetch (before any id exists), because a channel may rename its options.
        Map<String, FetchResult.OptionId> byVendorItemId = selling
                ? result.options().stream()
                        .filter(o -> o.vendorItemId() != null)
                        .collect(Collectors.toMap(FetchResult.OptionId::vendorItemId, o -> o, (a, b) -> a))
                : Map.of();
        Map<String, FetchResult.OptionId> byName = selling
                ? result.options().stream()
                        .filter(o -> o.optionName() != null)
                        .collect(Collectors.toMap(FetchResult.OptionId::optionName, o -> o, (a, b) -> a))
                : Map.of();

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        List<ListingStatusResponse.OptionStatus> optionStatuses = new ArrayList<>();
        for (ProductListingOption option : options) {
            FetchResult.OptionId match = option.getPlatformOptionId() != null
                    ? byVendorItemId.get(option.getPlatformOptionId())
                    : byName.get(option.getOptionName());
            if (match != null) {
                ProductListingOption.ProductListingOptionBuilder builder = option.toBuilder()
                        .platformOptionId(match.vendorItemId())
                        .sellerProductItemId(match.sellerProductItemId())
                        .approvalStatus(OptionApprovalStatus.APPROVED);
                if (option.getPlatformOptionId() == null) {
                    // 2609_39/D19 ③: the option just got its first market identifier, so the price that went
                    // out in the register payload is now the one live on the market. An option that ALREADY
                    // had an identifier is only being re-synced — no price was sent, so market_price stands.
                    builder.marketPrice(option.getSellingPrice()).marketPriceAt(LocalDateTime.now());
                }
                ProductListingOption updated = builder.build();
                productListingOptionRepository.save(updated);
                optionStatuses.add(ListingStatusResponse.OptionStatus.from(updated));
            } else {
                optionStatuses.add(ListingStatusResponse.OptionStatus.from(option));
            }
        }

        return new Refreshed(cell, acct, adapter, result, optionStatuses);
    }

    @Override
    @Transactional
    public ListingSyncResponse syncApprovals() {
        List<ProductListing> pending = productListingRepository.findPendingApproval();
        int swept = 0, promoted = 0, stillPending = 0, failed = 0;
        for (ProductListing cell : pending) {
            swept++;
            try {
                // Reuse the refresh core (same code path). Private call: no new tx boundary — see class doc.
                // 2609_74: the sweep refreshes only — it never looks a review reason up (one extra market
                // call per rejected listing would multiply across the whole sweep).
                Refreshed refreshed = refresh(cell.getId());
                if (refreshed.result().status() == ListingStatus.SELLING) {
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

    @Override
    public List<MarketOptionResponse> listMarketOptions(Long listingId) {
        ProductListing cell = requireMarketCell(listingId);
        ImportedProduct product = resolver.resolve(cell.getPlatform())
                .fetchProduct(cell.getPlatformProductId(), resolveAccount(cell));

        Map<String, ProductListingOption> holderByMarketId =
                productListingOptionRepository.findByProductListingId(listingId).stream()
                        .filter(o -> o.getPlatformOptionId() != null)
                        .collect(Collectors.toMap(ProductListingOption::getPlatformOptionId, o -> o, (a, b) -> a));

        return product.options().stream()
                .map(market -> {
                    ProductListingOption holder = market.vendorItemId() == null
                            ? null : holderByMarketId.get(market.vendorItemId());
                    return MarketOptionResponse.builder()
                            .itemName(market.itemName())
                            .vendorItemId(market.vendorItemId())
                            .sellerProductItemId(market.sellerProductItemId())
                            .salePrice(market.salePrice())
                            .linkedOptionId(holder == null ? null : holder.getId())
                            .linkedOptionName(holder == null ? null : holder.getOptionName())
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional
    public ListingStatusResponse.OptionStatus linkMarketOption(Long listingId, Long optionId, String vendorItemId) {
        ProductListing cell = requireMarketCell(listingId);
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        ProductListingOption option = options.stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("리스팅 옵션 아님"));
        // D13: only an option with NO id yet. Re-pointing an identified option is out of scope.
        if (option.getPlatformOptionId() != null) {
            throw new IllegalArgumentException("이미 쿠팡 옵션과 연결된 옵션입니다");
        }
        if (options.stream().anyMatch(o -> vendorItemId.equals(o.getPlatformOptionId()))) {
            throw new IllegalArgumentException("다른 옵션이 이미 사용 중인 쿠팡 옵션입니다");
        }

        // The id is never trusted from the client: it must exist on the market at this moment.
        ImportedProduct.Option market = resolver.resolve(cell.getPlatform())
                .fetchProduct(cell.getPlatformProductId(), resolveAccount(cell)).options().stream()
                .filter(o -> vendorItemId.equals(o.vendorItemId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("쿠팡에 없는 옵션입니다"));

        // Same rule the import path applies to an option it reads from the market (2609_22/D20,
        // 2609_39/D19 ④): an id exists ⇒ approved, and the market's price is the price that is live.
        ProductListingOption.ProductListingOptionBuilder builder = option.toBuilder()
                .platformOptionId(market.vendorItemId())
                .sellerProductItemId(market.sellerProductItemId())
                .approvalStatus(OptionApprovalStatus.APPROVED);
        if (market.salePrice() != null) {
            builder.marketPrice(market.salePrice()).marketPriceAt(LocalDateTime.now());
        }
        return ListingStatusResponse.OptionStatus.from(productListingOptionRepository.save(builder.build()));
    }

    /** Scoped cell that is on the market and on a platform whose products can be read back. */
    private ProductListing requireMarketCell(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (cell.getPlatformProductId() == null) {
            throw new IllegalArgumentException("미등록");
        }
        // fetchProduct's default implementation throws UnsupportedOperationException (→ 500) — gate first.
        if (cell.getPlatform() != Platform.COUPANG) {
            throw new IllegalArgumentException(cell.getPlatform() + " 옵션 연결 미지원");
        }
        return cell;
    }

    /** One refresh's outcome, before it is shaped into a response (2609_74). */
    private record Refreshed(ProductListing cell, MarketplaceAccount account, ListingChannel adapter,
                             FetchResult result, List<ListingStatusResponse.OptionStatus> options) {
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
