package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link ChannelStockPushService} (FEATURE_2611_19). Read-only transaction: it reads the saved rows and calls the
 * market, it writes nothing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelStockPushServiceImpl implements ChannelStockPushService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;

    @Override
    public List<StockPushLine> push(Map<Long, List<Long>> optionIdsByListingId) {
        List<StockPushLine> lines = new ArrayList<>();
        optionIdsByListingId.forEach((listingId, optionIds) -> productListingRepository.findScopedById(listingId)
                .ifPresent(listing -> lines.addAll(pushListing(listing, Set.copyOf(optionIds)))));
        return lines;
    }

    private List<StockPushLine> pushListing(ProductListing listing, Set<Long> optionIds) {
        Optional<ListingChannel> found = resolver.resolveOptional(listing.getPlatform())
                .filter(ListingChannel::pushesStock);
        if (found.isEmpty()) {
            return List.of();   // D7: no stock push on this platform — nothing to report
        }
        ListingChannel adapter = found.get();
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listing.getId())
                .stream().filter(o -> optionIds.contains(o.getId())).toList();
        List<ProductListingOption> active = options.stream().filter(o -> Boolean.TRUE.equals(o.getActive())).toList();
        Set<Long> targetIds = new HashSet<>();
        List<ProductListingOption> targets = active.isEmpty() ? List.of() : adapter.stockPushTargets(listing, active);
        targets.forEach(o -> targetIds.add(o.getId()));

        Map<Long, String> refused = Map.of();
        if (!targets.isEmpty()) {
            refused = send(adapter, listing, targets);
        }
        String sellerName = listing.getSeller() == null ? null : listing.getSeller().getSellerName();
        List<StockPushLine> lines = new ArrayList<>();
        for (ProductListingOption option : options) {
            StockPushLine.Result result;
            String message = null;
            if (!targetIds.contains(option.getId())) {
                result = StockPushLine.Result.SKIPPED;   // D25 switched off · no market number yet
            } else if (refused.containsKey(option.getId())) {
                result = StockPushLine.Result.FAILED;
                message = refused.get(option.getId());
            } else {
                result = StockPushLine.Result.PUSHED;
            }
            lines.add(new StockPushLine(listing.getId(), listing.getPlatform(), sellerName, option.getOptionName(),
                    result, message));
        }
        return lines;
    }

    /**
     * The account is looked up here, once, because something is about to be sent (D21). A missing / inactive account
     * or a thrown market call refuses every target with that text — the save stays (D6).
     */
    private Map<Long, String> send(ListingChannel adapter, ProductListing listing, List<ProductListingOption> targets) {
        try {
            MarketplaceAccount account = resolveAccount(listing);
            Map<Long, MasterProductOption> masters = mastersById(listing);
            List<StockPush> pushes = targets.stream()
                    .map(o -> new StockPush(o, ListingStockPolicy.resolve(o, linkedMaster(o, masters))))
                    .toList();
            return adapter.updateOptionStocks(listing, pushes, account);
        } catch (RuntimeException e) {
            return targets.stream().collect(Collectors.toMap(ProductListingOption::getId,
                    o -> String.valueOf(e.getMessage())));
        }
    }

    /** (seller, platform) account — the same rule as the price push (404 none, 400 inactive). */
    private MarketplaceAccount resolveAccount(ProductListing listing) {
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(listing.getSeller().getId(), listing.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", listing.getSeller().getId()));
        if (Boolean.FALSE.equals(acct.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        return acct;
    }

    private Map<Long, MasterProductOption> mastersById(ProductListing listing) {
        MasterProduct master = listing.getMasterProduct();
        if (master == null) {
            return Map.of();
        }
        return masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, Function.identity(), (a, b) -> a));
    }

    /** ⚠️ Reads the FK's id only — safe on a LAZY proxy. */
    private static MasterProductOption linkedMaster(ProductListingOption option, Map<Long, MasterProductOption> byId) {
        MasterProductOption linked = option.getMasterProductOption();
        return linked == null ? null : byId.get(linked.getId());
    }
}
