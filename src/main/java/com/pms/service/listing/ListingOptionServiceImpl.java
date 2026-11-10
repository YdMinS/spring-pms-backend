package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.request.SetOptionNamesRequest;
import com.pms.dto.request.SetOptionPricesRequest;
import com.pms.dto.request.SetOptionStocksRequest;
import com.pms.dto.response.ChannelApplyOptionNamesResponse;
import com.pms.dto.response.ChannelPriceUpdateResponse;
import com.pms.dto.response.ChannelStockUpdateResponse;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.PriceCalculator;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstListingRules;
import com.pms.service.esm.EsmPriceRules;
import com.pms.service.price.PriceHistoryRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PriceHistoryRecorder priceHistoryRecorder;
    /**
     * FEATURE_2611_19 / D26: the stock save commits through this template first, then {@link #channelStockPushService}
     * sends it — a market refusal can never roll the save back (D6).
     */
    private final TransactionTemplate transactionTemplate;
    private final ChannelStockPushService channelStockPushService;
    /** FEATURE_2611_19 / D18: the generated assets a whole-product price push re-sends. */
    private final GeneratedProductDataRepository generatedProductDataRepository;

    @Override
    public ListingOptionsResponse getOptions(Long listingId) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<Long, MasterProductOption> byId = masterOptionsById(listing);
        // Read is never a resync trigger → needsResync = false.
        return ListingOptionsResponse.of(listing, options, false,
                registrationName(listing, options), byId);
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
                        + " — 판매를 멈추려면 마켓 판매자 화면(쿠팡 WING · 11번가 셀러오피스)에서 처리하세요.");
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
        return ListingOptionsResponse.of(listing, updated, needsResync,
                registrationName(listing, updated), masterOptionsById(listing));
    }

    /**
     * Partial write: only the listed options change, and each value is independent (see
     * {@link ListingOptionService#setOptionStocks}). FEATURE_2611_19 / D26: {@code SUPPORTS} instead of the class's
     * read-only transaction — called from the controller there is no transaction, so the template's own transaction
     * commits the save before anything is sent (a caller that already holds a transaction keeps it). The market answer
     * only adds lines to the response.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public ChannelStockUpdateResponse setOptionStocks(Long listingId,
                                                      List<SetOptionStocksRequest.OptionStock> stocks) {
        ListingOptionsResponse saved = transactionTemplate.execute(status -> saveOptionStocks(listingId, stocks));
        List<Long> optionIds = stocks.stream().map(SetOptionStocksRequest.OptionStock::getOptionId).distinct().toList();
        List<StockPushLine> lines = channelStockPushService.push(Map.of(listingId, optionIds));
        return ChannelStockUpdateResponse.of(saved, lines);
    }

    /**
     * The stock save itself. Every validation runs before {@code saveAll} so a rejected request leaves nothing
     * half-applied — the same principle as the two guards above.
     */
    private ListingOptionsResponse saveOptionStocks(Long listingId, List<SetOptionStocksRequest.OptionStock> stocks) {
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
        Map<Long, MasterProductOption> masterById = masterOptionsById(listing);
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        for (SetOptionStocksRequest.OptionStock stock : stocks) {
            ProductListingOption option = byId.get(stock.getOptionId());
            Integer requested = stock.getStockQuantity();
            if (requested != null) {
                // 2609_22/D1: the ceiling comes from the LINKED master option; a channel-only option (D2)
                // resolves to null → 9999 (D6, unchanged behaviour for an option with no master).
                int ceiling = ListingStockPolicy.ceiling(linkedMaster(option, masterById));
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
        // FEATURE_2611_19 / D7: a market that takes the stock right away needs no [수정 요청] prompt.
        boolean pushesStock = resolver.resolveOptional(listing.getPlatform())
                .map(ListingChannel::pushesStock).orElse(false);
        boolean needsResync = !pushesStock && listing.getStatus() != null && listing.getStatus() != ListingStatus.DRAFT;
        return ListingOptionsResponse.of(listing, merged, needsResync,
                registrationName(listing, merged), masterById);
    }

    /**
     * Manual per-channel pricing (2609_19). See {@link ListingOptionService#setOptionPrices}. Two things make
     * this different from the two writes above: the new price is pushed to the market inside this request
     * (D4), and an option is saved only once the market has accepted it (D6) — so the market call happens
     * <em>before</em> {@code saveAll}, and its failures are caught rather than thrown (a thrown exception
     * would roll back the options that did succeed). An 11st or ESM listing (one product price + surcharges) sends the
     * whole product once instead, inside this transaction, and its refusal is thrown — nothing is saved
     * (FEATURE_2611_19 / D18 · {@link #pushWholeProduct}). An ESM listing carries the Auction price too
     * (FEATURE_2610_26 / D53 ②).
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
        // 2610_26/D53 ②: an ESM listing also works out each option's Auction price (same AUTO / MANUAL rule).
        boolean esm = listing.getPlatform() == Platform.ESM;
        Map<Long, TargetPrice> auctionTargets = new LinkedHashMap<>();
        for (SetOptionPricesRequest.OptionPrice price : prices) {
            ProductListingOption option = byId.get(price.getOptionId());
            if (price.getSellingPrice() != null) {
                BigDecimal salePrice = normalize(price.getSellingPrice());
                // 2610_13/D71: an 11st listing takes 10-won units only — refuse the whole request.
                if (ElevenstListingRules.isElevenst(listing) && !ElevenstListingRules.isTenWonUnit(salePrice)) {
                    throw new IllegalArgumentException(ElevenstListingRules.PRICE_UNIT_MESSAGE);
                }
                // 2610_26/D53 ②: so does an ESM listing, with its own message.
                if (esm && !EsmPriceRules.isTenWonUnit(salePrice)) {
                    throw new IllegalArgumentException(EsmPriceRules.PRICE_UNIT_MESSAGE);
                }
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
            if (esm) {
                auctionTargets.put(option.getId(), auctionTarget(listing, option, price.getAuctionSellingPrice()));
            }
        }

        // 2. Push to the market, then keep only what it accepted.
        // FEATURE_2611_19 / D18: 11st · ESM take the whole product instead (one call, refusal = nothing saved).
        ListingChannel adapter = resolver.resolveOptional(listing.getPlatform()).orElse(null);
        if (adapter != null && adapter.pushesPriceByUpdate()) {
            return pushWholeProduct(listing, options, byId, targets, auctionTargets, esm, adapter);
        }
        List<String> skipped = new ArrayList<>();
        List<ChannelPriceUpdateResponse.FailedOption> failed = new ArrayList<>();
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        int pushed = 0;
        // Resolved up front, and only when something is actually going to be sent: a missing/inactive account
        // is a request-level 404/400, not a per-option failure, so it must not land inside the catch below.
        boolean anyToPush = adapter != null && targets.keySet().stream().map(byId::get)
                .anyMatch(ListingOptionServiceImpl::sendsPrice);
        MarketplaceAccount account = anyToPush ? resolveAccount(listing) : null;
        for (Map.Entry<Long, TargetPrice> entry : targets.entrySet()) {
            ProductListingOption option = byId.get(entry.getKey());
            TargetPrice target = entry.getValue();
            // No market identifier (unapproved option / DRAFT cell), a switched-off option (FEATURE_2611_19 / D25) or
            // no adapter for this platform → the price is ours alone to keep; it reaches the market with the next
            // register/[수정 요청] (D5).
            if (adapter == null || !sendsPrice(option)) {
                skipped.add(option.getOptionName());
                // pushed=false: nothing reached the market, so market_price must stay as it was (2609_39/D19).
                toSave.put(option.getId(), applied(option, target, auctionTargets.get(option.getId()), false));
                continue;
            }
            try {
                adapter.updateOptionPrice(option, target.salePrice(), account);
                toSave.put(option.getId(), applied(option, target, null, true));
                pushed++;
            } catch (Exception e) {
                // Caught, not propagated: a rejected option must not roll back the ones that went through,
                // and it must not be saved either (DB and market would then disagree, D6).
                failed.add(new ChannelPriceUpdateResponse.FailedOption(option.getOptionName(), e.getMessage()));
            }
        }
        productListingOptionRepository.saveAll(List.copyOf(toSave.values()));

        recordPriceHistory(listing, byId, toSave, esm);
        return priceResponse(listing, options, toSave, pushed, skipped, failed);
    }

    /**
     * FEATURE_2611_19 / D18 · D25: an 11st / ESM listing. Every target price is saved; an option is sent (pushed) when it
     * is active and the listing can take the whole product now — a market number, generated assets and a market number
     * on every active option. Then the adapter re-sends the whole product once; its exception propagates and rolls the
     * whole save back (no price history either). On success every active option's market price record = the local
     * price just sent (D33). Status, tag history and {@code needsMarketSync} are not touched (the
     * [수정 요청] after-work of {@code ListingRegistrationServiceImpl.updateRequest} is not run).
     */
    private ChannelPriceUpdateResponse pushWholeProduct(ProductListing listing, List<ProductListingOption> options,
                                                        Map<Long, ProductListingOption> byId,
                                                        Map<Long, TargetPrice> targets,
                                                        Map<Long, TargetPrice> auctionTargets, boolean esm,
                                                        ListingChannel adapter) {
        GeneratedProductData gen = generatedProductDataRepository.findByProductListingId(listing.getId()).orElse(null);
        boolean sendable = StringUtils.hasText(listing.getPlatformProductId()) && gen != null
                && options.stream().filter(o -> Boolean.TRUE.equals(o.getActive()))
                        .allMatch(o -> StringUtils.hasText(o.getPlatformOptionId()));
        List<String> skipped = new ArrayList<>();
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        int pushed = 0;
        for (Map.Entry<Long, TargetPrice> entry : targets.entrySet()) {
            ProductListingOption option = byId.get(entry.getKey());
            boolean send = sendable && Boolean.TRUE.equals(option.getActive());
            toSave.put(option.getId(), applied(option, entry.getValue(), auctionTargets.get(option.getId()), send));
            if (send) {
                pushed++;
            } else {
                skipped.add(option.getOptionName());
            }
        }
        // Resolved before the save: a missing / inactive account is a request-level 404 / 400 (D21).
        MarketplaceAccount account = pushed > 0 ? resolveAccount(listing) : null;
        productListingOptionRepository.saveAll(List.copyOf(toSave.values()));
        Map<Long, ProductListingOption> shown = new LinkedHashMap<>();
        if (pushed > 0) {
            adapter.update(listing, gen, account);
            // D33: the update sent every active option's local price, so the untouched active options' market price
            // record follows too (the edited ones got theirs from applied above).
            List<ProductListingOption> sentAsIs = options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getActive()) && !toSave.containsKey(o.getId()))
                    .map(o -> marketPriceRecorded(o, esm))
                    .toList();
            if (!sentAsIs.isEmpty()) {
                productListingOptionRepository.saveAll(sentAsIs);
            }
            sentAsIs.forEach(o -> shown.put(o.getId(), o));
        }
        recordPriceHistory(listing, byId, toSave, esm);
        shown.putAll(toSave);
        return priceResponse(listing, options, shown, pushed, skipped, List.of());
    }

    /**
     * FEATURE_2611_19 / D33: an option the whole-product update sent unchanged — its local price is now the market's
     * (the Auction price too on ESM), as after a register (ListingRegistrationServiceImpl 2609_39/D19 ③).
     */
    private static ProductListingOption marketPriceRecorded(ProductListingOption option, boolean esm) {
        ProductListingOption.ProductListingOptionBuilder builder = option.toBuilder()
                .marketPrice(option.getSellingPrice()).marketPriceAt(LocalDateTime.now(SyncWindow.KST));
        if (esm) {
            builder.auctionMarketPrice(option.getAuctionSellingPrice());
        }
        return builder.build();
    }

    /**
     * Price history hook ④ (PLAN 2609_28 D23). Only what was actually saved is recorded — an option the market rejected
     * keeps its old price, so it is not a change. Dropping a manual price back to AUTO is a movement too and belongs to
     * this path, hence MANUAL for both directions.
     */
    private void recordPriceHistory(ProductListing listing, Map<Long, ProductListingOption> byId,
                                    Map<Long, ProductListingOption> toSave, boolean esm) {
        priceHistoryRecorder.recordSellingPrices(listing,
                toSave.values().stream()
                        .map(saved -> new PriceHistoryRecorder.SellingPriceChange(saved,
                                byId.get(saved.getId()).getSellingPrice(), saved.getSellingPrice()))
                        .toList(),
                PriceChangeReason.MANUAL);
        // 2610_26/D53 ③: the Auction side of the same save, as its own target type.
        if (esm) {
            priceHistoryRecorder.recordAuctionSellingPrices(listing,
                    toSave.values().stream()
                            .map(saved -> new PriceHistoryRecorder.SellingPriceChange(saved,
                                    byId.get(saved.getId()).getAuctionSellingPrice(), saved.getAuctionSellingPrice()))
                            .toList(),
                    PriceChangeReason.MANUAL);
        }
    }

    /** The full option set with the saved rows swapped in (setOptionStocks' closing block) + the push counts. */
    private ChannelPriceUpdateResponse priceResponse(ProductListing listing, List<ProductListingOption> options,
                                                     Map<Long, ProductListingOption> toSave, int pushed,
                                                     List<String> skipped,
                                                     List<ChannelPriceUpdateResponse.FailedOption> failed) {
        List<ProductListingOption> merged = options.stream()
                .map(option -> toSave.getOrDefault(option.getId(), option))
                .toList();
        // The market side of this change is already done, so there is nothing to re-send (D15) — and the
        // active option set did not move, so the registration name cannot have changed either.
        ListingOptionsResponse body = ListingOptionsResponse.of(listing, merged, false,
                registrationName(listing, merged), masterOptionsById(listing));
        return new ChannelPriceUpdateResponse(body, pushed, skipped, failed);
    }

    /**
     * Per-channel option naming (2609_22/D3). Same shape as {@link #setOptionStocks} — validate everything,
     * then {@code saveAll} — with one extra rule: names must stay unique inside the cell, judged on the
     * <b>merged</b> result (saved rows swapped in), because Coupang rejects a duplicate {@code itemName}.
     */
    @Override
    @Transactional
    public ListingOptionsResponse setOptionNames(Long listingId, List<SetOptionNamesRequest.Item> names) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // A no-op save is a mistake, not a valid request (same rule/message as setOptionStocks).
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("변경할 옵션이 없습니다");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<Long, ProductListingOption> byId = options.stream()
                .collect(Collectors.toMap(ProductListingOption::getId, Function.identity()));
        if (!byId.keySet().containsAll(names.stream()
                .map(SetOptionNamesRequest.Item::getOptionId).toList())) {
            throw new IllegalArgumentException("리스팅 옵션 아님");
        }

        Map<Long, MasterProductOption> masterById = masterOptionsById(listing);
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        for (SetOptionNamesRequest.Item item : names) {
            ProductListingOption option = byId.get(item.getOptionId());
            String requested = item.getOptionName() == null ? null : item.getOptionName().trim();
            if (StringUtils.hasText(requested)) {
                // 2610_13/D71: a typed 11st name that breaks the 11st rule is refused, not rewritten.
                if (ElevenstListingRules.isElevenst(listing) && !ElevenstListingRules.isValidName(requested)) {
                    throw new IllegalArgumentException(ElevenstListingRules.INVALID_NAME_MESSAGE);
                }
                // D3: the channel named it → a master rename must not overwrite it (D4).
                toSave.put(option.getId(), option.toBuilder()
                        .optionName(requested)
                        .optionNameSource(GeneratedContentSource.MANUAL_OVERRIDE)
                        .build());
                continue;
            }
            // Blank = back to AUTO, which only exists when a master option is behind this row (D2).
            MasterProductOption master = linkedMaster(option, masterById);
            if (master == null) {
                throw new IllegalArgumentException("채널 전용 옵션은 되돌릴 마스터 옵션명이 없습니다");
            }
            toSave.put(option.getId(), option.toBuilder()
                    .optionName(ListingOptionNames.nameForCell(listing, master.getName()))
                    .optionNameSource(GeneratedContentSource.AUTO)
                    .build());
        }

        // 2609_74/D32·D33: a name-locked option is matched BY NAME when the approval result comes back, so a new
        // name loses that result. One such item rejects the whole request (400, nothing saved). An item whose
        // name stays the same and only the source moves (blank → master name equal to the current one) passes.
        boolean renamesLockedOption = toSave.values().stream().anyMatch(saved -> {
            ProductListingOption before = byId.get(saved.getId());
            return MarketOptionPolicy.nameLocked(listing, before)
                    && !Objects.equals(before.getOptionName(), saved.getOptionName());
        });
        if (renamesLockedOption) {
            throw new IllegalArgumentException(MarketOptionPolicy.NAME_LOCKED_MESSAGE);
        }

        // Return the full option set, with the saved rows swapped in (setOptionStocks' closing block) — and
        // judge uniqueness on exactly that view, before anything is written.
        List<ProductListingOption> merged = options.stream()
                .map(option -> toSave.getOrDefault(option.getId(), option))
                .toList();
        assertNamesUnique(merged);
        productListingOptionRepository.saveAll(List.copyOf(toSave.values()));
        // 2609_74/D16: a market-carried option whose name actually moved → the cell disagrees with the market.
        boolean marketNameChanged = toSave.values().stream().anyMatch(saved -> {
            ProductListingOption before = byId.get(saved.getId());
            return !Objects.equals(before.getOptionName(), saved.getOptionName())
                    && MarketOptionPolicy.carriedOnMarket(listing, before);
        });
        if (marketNameChanged && !listing.isNeedsMarketSync()) {
            productListingRepository.save(listing.toBuilder().needsMarketSync(true).build());
        }

        boolean needsResync = listing.getStatus() != null && listing.getStatus() != ListingStatus.DRAFT;
        return ListingOptionsResponse.of(listing, merged, needsResync,
                registrationName(listing, merged), masterById);
    }

    @Override
    @Transactional
    public ChannelApplyOptionNamesResponse applyMasterOptionNames(Long listingId) {
        ProductListing listing = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (listing.getMasterProduct() == null) {
            throw new IllegalArgumentException("마스터에 연결되지 않은 판매상품입니다");
        }

        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId);
        Map<Long, MasterProductOption> masterById = masterOptionsById(listing);
        Map<Long, ProductListingOption> toSave = new LinkedHashMap<>();
        List<String> skippedAwaitingId = new ArrayList<>();
        boolean marketNameChanged = false;
        for (ProductListingOption option : options) {
            MasterProductOption master = linkedMaster(option, masterById);
            if (master == null) {
                continue;   // channel-only option (2609_22/D2) — no master name to take
            }
            // 2610_13/D66 · 2610_26/D26: an 11st cell compares against and takes the 11st-safe form of the master
            // name, an ESM cell its 50-byte cut.
            String masterName = ListingOptionNames.nameForCell(listing, master.getName());
            boolean alreadyApplied = masterName.equals(option.getOptionName())
                    && option.getOptionNameSource() == GeneratedContentSource.AUTO;
            if (alreadyApplied) {
                continue;
            }
            // 2609_74/D32·D33: skip condition = the name lock (a REJECTED cell is renamed, then re-sent).
            if (MarketOptionPolicy.nameLocked(listing, option)) {
                skippedAwaitingId.add(option.getOptionName());
                continue;
            }
            toSave.put(option.getId(), option.toBuilder()
                    .optionName(masterName)
                    .optionNameSource(GeneratedContentSource.AUTO)
                    .build());
            marketNameChanged = marketNameChanged
                    || (!masterName.equals(option.getOptionName())
                        && MarketOptionPolicy.carriedOnMarket(listing, option));
        }

        List<ProductListingOption> merged = options.stream()
                .map(option -> toSave.getOrDefault(option.getId(), option))
                .toList();
        assertNamesUnique(merged);
        if (!toSave.isEmpty()) {
            productListingOptionRepository.saveAll(List.copyOf(toSave.values()));
        }
        // 2609_74/D16
        if (marketNameChanged && !listing.isNeedsMarketSync()) {
            productListingRepository.save(listing.toBuilder().needsMarketSync(true).build());
        }
        return ChannelApplyOptionNamesResponse.builder()
                .updatedOptions(toSave.size())
                .skippedAwaitingId(skippedAwaitingId)
                .build();
    }

    /**
     * Two options of one cell may not share a name: it becomes the Coupang {@code itemName}, and a duplicate
     * there is a marketplace error. Thrown before any save — no partially applied batch.
     */
    private static void assertNamesUnique(List<ProductListingOption> options) {
        Set<String> seen = new HashSet<>();
        for (ProductListingOption option : options) {
            if (!seen.add(option.getOptionName())) {
                throw new IllegalArgumentException("같은 이름의 옵션이 이미 있습니다: " + option.getOptionName());
            }
        }
    }

    /** D5 · FEATURE_2611_19 / D25: a per-option price push goes to an active option the market has numbered. */
    private static boolean sendsPrice(ProductListingOption option) {
        return Boolean.TRUE.equals(option.getActive()) && StringUtils.hasText(option.getPlatformOptionId());
    }

    /** The prices an option is about to get, held until the market has accepted them (D6). */
    private record TargetPrice(BigDecimal salePrice, BigDecimal originalPrice, GeneratedContentSource source) {
    }

    /**
     * 2610_26/D53 ②: an ESM option's Auction target — a typed price (10-won unit, else the whole request is 400) is
     * MANUAL_OVERRIDE, null is the calculated Auction price as AUTO. {@code originalPrice} stays null (D52).
     */
    private TargetPrice auctionTarget(ProductListing listing, ProductListingOption option, BigDecimal requested) {
        if (requested != null) {
            BigDecimal auction = normalize(requested);
            if (!EsmPriceRules.isTenWonUnit(auction)) {
                throw new IllegalArgumentException(EsmPriceRules.PRICE_UNIT_MESSAGE);
            }
            return new TargetPrice(auction, null, GeneratedContentSource.MANUAL_OVERRIDE);
        }
        return new TargetPrice(listingAssetService.quoteAuctionOptionPrice(listing, option), null,
                GeneratedContentSource.AUTO);
    }

    /**
     * @param pushed true only when the marketplace accepted this exact price — then and only then is it also
     *               the price that is live on the market (2609_39/D19 ②). Writing {@code marketPrice} here for an
     *               option this path never sent would make that column lie on its very first day.
     * @param auction the Auction target of an ESM option (2610_26/D53 ②), null on every other platform
     */
    private static ProductListingOption applied(ProductListingOption option, TargetPrice target, TargetPrice auction,
                                                boolean pushed) {
        ProductListingOption.ProductListingOptionBuilder builder = option.toBuilder()
                .sellingPrice(target.salePrice())
                .originalPrice(target.originalPrice())
                .priceSource(target.source());
        if (auction != null) {
            builder.auctionSellingPrice(auction.salePrice()).auctionPriceSource(auction.source());
        }
        if (pushed) {
            builder.marketPrice(target.salePrice()).marketPriceAt(LocalDateTime.now(SyncWindow.KST));
            // FEATURE_2611_19 / D18: an ESM whole-product push sends the Auction price as well.
            if (auction != null) {
                builder.auctionMarketPrice(auction.salePrice());
            }
        }
        return builder.build();
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
     * This listing's master options keyed by <b>id</b> — the single master↔channel matching axis since
     * 2609_22/D1 (never the option name: a channel may rename its options). Resolves each option's stock
     * ceiling (102) and feeds the option response. Legacy cell without a master → empty map (ceiling 9999).
     */
    private Map<Long, MasterProductOption> masterOptionsById(ProductListing listing) {
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return Map.of();
        }
        return masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * The master option a cell option is linked to, or null for a channel-only option (2609_22/D2).
     * ⚠️ Reads the FK's id only — safe on a LAZY proxy (no extra query per option).
     */
    private static MasterProductOption linkedMaster(ProductListingOption option,
                                                    Map<Long, MasterProductOption> byId) {
        MasterProductOption linked = option.getMasterProductOption();
        return linked == null ? null : byId.get(linked.getId());
    }

    /**
     * The registration name (「등록상품명」, 67) for this listing's current active options — carried in the response so the
     * front can patch the matrix cell in place after a toggle. The listing's own stored name wins (FEATURE_2611_11 /
     * D4 · D15); else the automatic name. master null → the listing's display name (backfill transition window).
     */
    private String registrationName(ProductListing listing, List<ProductListingOption> options) {
        if (StringUtils.hasText(listing.getRegistrationName())) {
            return listing.getRegistrationName();
        }
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return listing.getName();
        }
        // 2609_22/D7: the generator resolves the single-option case through the FK (and falls back to the
        // cell's own BOM for a channel-only option), so it needs the option rows, not their names.
        List<ProductListingOption> activeOptions = options.stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .toList();
        // 69: suffix = channel(this cell's account) ?? master ?? seller ?? system (single cell = one account query).
        OptionCheckSuffix suffix = optionCheckSuffixResolver.resolve(listing);
        return registrationNameGenerator.generate(master, activeOptions, suffix);
    }
}
