package com.pms.service.listing.shipping;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.listing.MarketOptionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EsmShippingRepricer} — the same shape as {@code ElevenstShippingRepricerImpl} (FEATURE_2610_26 / D65).
 *
 * <p>⚠️ The class is NOT {@code @Transactional}: the loop is plain and each cell goes through {@link #self} (the
 * proxy) so {@code REQUIRES_NEW} applies — one failed cell rolls back only itself.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsmShippingRepricerImpl implements EsmShippingRepricer {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ListingAssetService listingAssetService;

    /** Self proxy so {@link #repriceOne} goes through the {@code REQUIRES_NEW} advice (not a direct call). */
    @Autowired
    @Lazy
    private EsmShippingRepricer self;

    @Override
    public int repriceAccount(Long accountId) {
        return repriceCells(productListingRepository.findCellsOfAccount(accountId));
    }

    @Override
    public int repriceListings(List<Long> listingIds) {
        List<ProductListing> cells = new ArrayList<>();
        for (Long listingId : listingIds) {
            productListingRepository.findScopedById(listingId).ifPresent(cells::add);
        }
        return repriceCells(cells);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repriceOne(Long listingId) {
        // Re-read inside this boundary — the re-price walks LAZY links (master, category, seller).
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        Map<Long, BigDecimal> before = new HashMap<>();
        Map<Long, BigDecimal> auctionBefore = new HashMap<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(listingId)) {
            before.put(option.getId(), option.getSellingPrice());
            auctionBefore.put(option.getId(), option.getAuctionSellingPrice());
        }

        listingAssetService.recalculateOptionPrices(cell);

        // D65 · 11st D121: only a Gmarket or Auction price that really moved on an option the market carries makes
        // the cell pending.
        boolean marketPriceChanged = productListingOptionRepository.findByProductListingId(listingId).stream()
                .anyMatch(option -> (!sameAmount(before.get(option.getId()), option.getSellingPrice())
                        || !sameAmount(auctionBefore.get(option.getId()), option.getAuctionSellingPrice()))
                        && MarketOptionPolicy.carriedOnMarket(cell, option));
        if (marketPriceChanged && !cell.isNeedsMarketSync()) {
            productListingRepository.save(cell.toBuilder().needsMarketSync(true).build());
        }
    }

    private int repriceCells(List<ProductListing> cells) {
        int failed = 0;
        for (ProductListing cell : cells) {
            if (cell.getPlatform() != Platform.ESM) {
                continue;   // D65: only an ESM price reads the ESM shipping settings
            }
            try {
                self.repriceOne(cell.getId());   // proxy → REQUIRES_NEW: independent commit per cell
            } catch (Exception e) {
                failed++;   // D65 · 11st D120: the saved shipping stays; this cell keeps its old prices
                log.warn("[ESM-REPRICE] cellId={} re-price failed: {}", cell.getId(), e.getMessage());
            }
        }
        return failed;
    }

    /** The same amount at a different scale is no change (10000 vs 10000.00). */
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
