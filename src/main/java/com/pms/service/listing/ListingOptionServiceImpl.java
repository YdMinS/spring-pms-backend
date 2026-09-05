package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.request.SetOptionPricesRequest;
import com.pms.dto.request.SetOptionStocksRequest;
import com.pms.dto.response.ChannelPriceUpdateResponse;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.PriceCalculator;
import com.pms.service.RegistrationNameGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-channel option selection (FEATURE_2608_06 / 42). See {@link ListingOptionService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingOptionServiceImpl implements ListingOptionService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    // 2609_19: manual pricing needs the display (strike-through) price (D7), the calculated price to fall
    // back to (D3) and the channel adapter + account to push with (D4). No cycle: neither ListingAssetService
    // nor the Coupang adapter depends on this service.
    private final PriceCalculator priceCalculator;
    private final ListingAssetService listingAssetService;
    private final ListingChannelResolver resolver;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Override
    public ListingOptionsResponse getOptions(Long listingId) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<String, MasterProductOption> byName = masterOptionsByName(listing);
        // Read is never a resync trigger → needsResync = false.
        return ListingOptionsResponse.of(listing, options, false,
                registrationName(listing, options, byName), byName);
    }

    /**
     * ⚠️ On a cell that reached the market, an option that is registered there may not be unchecked: Coupang
     * cannot physically delete an approved option, so switching it off locally would only hide it from the next
     * payload while it keeps selling. Turning options <em>on</em> is always allowed, and so is undoing that
     * before the cell is re-pushed (the option is not on the market yet).
     *
     * <p>The lock reads {@link ProductListingOption#isMarketRegistered()}; 84's master-option lock is the
     * superset that adds {@code active} on top of it.</p>
     */
    @Override
    @Transactional
    public ListingOptionsResponse setActiveOptions(Long listingId, List<Long> activeOptionIds) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // At least one active option required (an empty product cannot be pushed).
        if (activeOptionIds == null || activeOptionIds.isEmpty()) {
            throw new IllegalArgumentException("활성 옵션 최소 1개");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Set<Long> ownIds = options.stream().map(ProductListingOption::getId).collect(Collectors.toSet());
        Set<Long> requested = Set.copyOf(activeOptionIds);
        // Every requested id must belong to this listing (reject other-listing / master option ids mixed in).
        if (!ownIds.containsAll(requested)) {
            throw new IllegalArgumentException("리스팅 옵션 아님");
        }

        // A cell that reached the market: an option that physically exists there cannot be taken back off by
        // unchecking it (Coupang cannot delete an approved option), so refuse instead of pretending it worked.
        // ⚠️ Thrown before saveAll — no partially applied state, same place/exception type as the guards above.
        if (listing.getPlatformProductId() != null) {
            List<String> turningOff = options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getActive()) && !requested.contains(o.getId()))
                    .filter(ProductListingOption::isMarketRegistered)
                    .map(ProductListingOption::getOptionName)
                    .toList();
            if (!turningOff.isEmpty()) {
                throw new IllegalArgumentException(
                        "마켓에 등록된 옵션은 뺄 수 없습니다: " + String.join(", ", turningOff)
                        + " — 판매를 멈추려면 쿠팡 WING 에서 처리하세요.");
            }
        }

        // Immutable entity → toBuilder each option, then saveAll explicitly (no dirty-checking reliance so the
        // save is verifiable, and the two validation failures above never call saveAll).
        List<ProductListingOption> updated = options.stream()
                .map(option -> option.toBuilder()
                        .active(requested.contains(option.getId()))
                        .build())
                .toList();
        productListingOptionRepository.saveAll(updated);

        // Listing-level needsResync (single boolean, OR aggregate): if this listing is already pushed, the
        // active-set change alone does not reach the market → the front must re-register/update. No auto-push here.
        boolean needsResync = listing.getStatus() != null && listing.getStatus() != ListingStatus.DRAFT;
        Map<String, MasterProductOption> byName = masterOptionsByName(listing);
        return ListingOptionsResponse.of(listing, updated, needsResync,
                registrationName(listing, updated, byName), byName);
    }

    /**
     * Partial write: only the listed options change, and each value is independent (see
     * {@link ListingOptionService#setOptionStocks}). Every validation runs before {@code saveAll} so a rejected
     * request leaves nothing half-applied — the same principle as the two guards above.
     */
    @Override
    @Transactional
    public ListingOptionsResponse setOptionStocks(Long listingId, List<SetOptionStocksRequest.OptionStock> stocks) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // A no-op save is a mistake, not a valid request — say so instead of returning "success".
        if (stocks == null || stocks.isEmpty()) {
            throw new IllegalArgumentException("변경할 옵션이 없습니다");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<Long, ProductListingOption> byId = options.stream()
                .collect(Collectors.toMap(ProductListingOption::getId, Function.identity()));
        // Every requested id must belong to this listing (same rule/message/exception as setActiveOptions).
        if (!byId.keySet().containsAll(stocks.stream()
                .map(SetOptionStocksRequest.OptionStock::getOptionId).toList())) {
            throw new IllegalArgumentException("리스팅 옵션 아님");
        }

        // D5: a channel value may not exceed its option's ceiling (master stock ?? 9999). Validate the whole
        // batch first, then save — a rejected option must not leave the earlier ones applied.
        Map<String, MasterProductOption> byName = masterOptionsByName(listing);
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        for (SetOptionStocksRequest.OptionStock stock : stocks) {
            ProductListingOption option = byId.get(stock.getOptionId());
            Integer requested = stock.getStockQuantity();
            if (requested != null) {
                int ceiling = ListingStockPolicy.ceiling(byName.get(option.getOptionName()));
                if (requested > ceiling) {
                    throw new IllegalArgumentException(option.getOptionName()
                            + ": 채널 재고는 마스터 재고(" + ceiling + ")보다 클 수 없습니다");
                }
            }
            // null = clear the override (back to inheriting the master value).
            toSave.put(option.getId(), option.toBuilder().stockQuantity(requested).build());
        }
        productListingOptionRepository.saveAll(List.copyOf(toSave.values()));

        // Return the full option set, with the saved rows swapped in (untouched options keep their values).
        List<ProductListingOption> merged = options.stream()
                .map(option -> toSave.getOrDefault(option.getId(), option))
                .toList();
        boolean needsResync = listing.getStatus() != null && listing.getStatus() != ListingStatus.DRAFT;
        return ListingOptionsResponse.of(listing, merged, needsResync,
                registrationName(listing, merged, byName), byName);
    }

    /**
     * Manual per-channel pricing (2609_19). See {@link ListingOptionService#setOptionPrices}. Two things make
     * this different from the two writes above: the new price is pushed to the market inside this request
     * (D4), and an option is saved only once the market has accepted it (D6) — so the market call happens
     * <em>before</em> {@code saveAll}, and its failures are caught rather than thrown (a thrown exception
     * would roll back the options that did succeed).
     */
    @Override
    @Transactional
    public ChannelPriceUpdateResponse setOptionPrices(Long listingId,
                                                      List<SetOptionPricesRequest.OptionPrice> prices) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // A no-op save is a mistake, not a valid request (same rule/message as setOptionStocks).
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("변경할 옵션이 없습니다");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<Long, ProductListingOption> byId = options.stream()
                .collect(Collectors.toMap(ProductListingOption::getId, Function.identity()));
        if (!byId.keySet().containsAll(prices.stream()
                .map(SetOptionPricesRequest.OptionPrice::getOptionId).toList())) {
            throw new IllegalArgumentException("리스팅 옵션 아님");
        }

        // 1. Work out every target price first. Nothing is saved and nothing is sent yet: an option returning
        //    to AUTO that cannot be priced must fail the whole request rather than leave a half-applied batch
        //    (D16) — PriceCalculator throws IllegalArgumentException → 400, message passed through.
        Map<Long, TargetPrice> targets = new LinkedHashMap<>();
        for (SetOptionPricesRequest.OptionPrice price : prices) {
            ProductListingOption option = byId.get(price.getOptionId());
            if (price.getSellingPrice() != null) {
                BigDecimal salePrice = normalize(price.getSellingPrice());
                targets.put(option.getId(), new TargetPrice(salePrice,
                        priceCalculator.displayOriginalPrice(listing, salePrice),
                        GeneratedContentSource.MANUAL_OVERRIDE));
            } else {
                // null = drop the manual price. We must know the value it returns to before touching anything,
                // hence the calculate-only seam (recalculateOptionPrices would save mid-loop).
                PriceCalculator.PriceResult quoted = listingAssetService.quoteOptionPrice(listing, option);
                targets.put(option.getId(), new TargetPrice(quoted.salePrice(), quoted.originalPrice(),
                        GeneratedContentSource.AUTO));
            }
        }

        // 2. Push to the market, then keep only what it accepted.
        ListingChannel adapter = resolver.resolveOptional(listing.getPlatform()).orElse(null);
        List<String> skipped = new ArrayList<>();
        List<ChannelPriceUpdateResponse.FailedOption> failed = new ArrayList<>();
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        int pushed = 0;
        // Resolved up front, and only when something is actually going to be sent: a missing/inactive account
        // is a request-level 404/400, not a per-option failure, so it must not land inside the catch below.
        boolean anyToPush = adapter != null && targets.keySet().stream()
                .anyMatch(id -> StringUtils.hasText(byId.get(id).getPlatformOptionId()));
        MarketplaceAccount account = anyToPush ? resolveAccount(listing) : null;
        for (Map.Entry<Long, TargetPrice> entry : targets.entrySet()) {
            ProductListingOption option = byId.get(entry.getKey());
            TargetPrice target = entry.getValue();
            // No market identifier (unapproved option / DRAFT cell) or no adapter for this platform → the
            // price is ours alone to keep; it reaches the market with the next register/[수정 요청] (D5).
            if (adapter == null || !StringUtils.hasText(option.getPlatformOptionId())) {
                skipped.add(option.getOptionName());
                toSave.put(option.getId(), applied(option, target));
                continue;
            }
            try {
                adapter.updateOptionPrice(option, target.salePrice(), account);
                toSave.put(option.getId(), applied(option, target));
                pushed++;
            } catch (Exception e) {
                // Caught, not propagated: a rejected option must not roll back the ones that went through,
                // and it must not be saved either (DB and market would then disagree, D6).
                failed.add(new ChannelPriceUpdateResponse.FailedOption(option.getOptionName(), e.getMessage()));
            }
        }
        productListingOptionRepository.saveAll(List.copyOf(toSave.values()));

        // Return the full option set with the saved rows swapped in (setOptionStocks' closing block).
        List<ProductListingOption> merged = options.stream()
                .map(option -> toSave.getOrDefault(option.getId(), option))
                .toList();
        Map<String, MasterProductOption> byName = masterOptionsByName(listing);
        // The market side of this change is already done, so there is nothing to re-send (D15) — and the
        // active option set did not move, so the registration name cannot have changed either.
        ListingOptionsResponse body = ListingOptionsResponse.of(listing, merged, false,
                registrationName(listing, merged, byName), byName);
        return new ChannelPriceUpdateResponse(body, pushed, skipped, failed);
    }

    /** The prices an option is about to get, held until the market has accepted them (D6). */
    private record TargetPrice(BigDecimal salePrice, BigDecimal originalPrice, GeneratedContentSource source) {
    }

    private static ProductListingOption applied(ProductListingOption option, TargetPrice target) {
        return option.toBuilder()
                .sellingPrice(target.salePrice())
                .originalPrice(target.originalPrice())
                .priceSource(target.source())
                .build();
    }

    /**
     * D13: pin the value to whole won. What we send to the market and what stays in the DB must be the same
     * number (D6), and a decimal point in the price path is a 400 from Coupang. The 10-won rounding is NOT
     * applied — that is a rule of the automatic calculation, and a hand-set price is left as the user typed it.
     */
    private static BigDecimal normalize(BigDecimal input) {
        return input.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    /** (seller, platform) account for the cell — same rule as the register path (404 none, 400 inactive). */
    private MarketplaceAccount resolveAccount(ProductListing listing) {
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(listing.getSeller().getId(), listing.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", listing.getSeller().getId()));
        if (Boolean.FALSE.equals(acct.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        return acct;
    }

    /**
     * This listing's master options keyed by name — the axis that resolves each option's stock ceiling (102)
     * and feeds the registration name (67). Legacy cell without a master → empty map (every ceiling 9999).
     * Queried once per request and shared, so the two consumers never issue the same query twice.
     */
    private Map<String, MasterProductOption> masterOptionsByName(ProductListing listing) {
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return Map.of();
        }
        return masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                .collect(Collectors.toMap(MasterProductOption::getName, Function.identity(), (a, b) -> a));
    }

    /**
     * The auto-generated registration name (등록상품명, 67) for this listing's current active options — carried in
     * the response so the front can patch the matrix cell in place after a toggle. master null → the listing's
     * display name (backfill transition window).
     */
    private String registrationName(ProductListing listing, List<ProductListingOption> options,
                                    Map<String, MasterProductOption> masterOptionsByName) {
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return listing.getName();
        }
        List<String> activeNames = options.stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .map(ProductListingOption::getOptionName)
                .toList();
        // 69: suffix = channel(this cell's account) ?? master ?? seller ?? system (single cell = one account query).
        OptionCheckSuffix suffix = optionCheckSuffixResolver.resolve(listing);
        return registrationNameGenerator.generate(master, activeNames,
                List.copyOf(masterOptionsByName.values()), suffix);
    }
}
