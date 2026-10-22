package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.PriceCalculator;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.price.PriceHistoryRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the prices read from the market (FEATURE_2610_27 / D14 · D15 · D19 · D31 · D32 · D36).
 *
 * <p><b>Purpose</b>: the one place that writes a market read into {@code product_listing_option} — used by
 * [승인 새로고침] ({@code ListingRegistrationServiceImpl.refresh}) and the 02:05 market price batch
 * ({@code MarketPriceSyncServiceImpl.syncOne}). <b>File</b>: {@code service/listing/MarketPriceApplier.java}</p>
 *
 * <p>Per option (an ESM option has two prices — Gmarket and Auction):</p>
 * <ul>
 *   <li>a price null or ≤ 0 (on ESM: either of the two) → the option keeps all its previous values (D36)</li>
 *   <li>market price ← the read price, check time ← now (KST wall clock), even when nothing else changes (D19 · D26 ·
 *       D32)</li>
 *   <li>per price, read ≠ system price → system price ← read price, origin ← {@code MANUAL_OVERRIDE}, a
 *       {@code MARKET_SYNC} history row (D14 · D15); a changed Gmarket / single price also sets the display original
 *       price ← {@code displayOriginalPrice} of the new price, or null when it cannot be computed (D31)</li>
 * </ul>
 *
 * <p>❌ Never route this through {@code ListingOptionService.setOptionPrices} — that path sends to the market.
 * ❌ Never recalculate a price here — the value written is the value read (2609_39 D1). ❌ Never set
 * {@code needs_market_sync}. Runs in the caller's transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class MarketPriceApplier {

    private final ProductListingOptionRepository productListingOptionRepository;
    private final PriceCalculator priceCalculator;
    private final PriceHistoryRecorder priceHistoryRecorder;

    /**
     * Apply one listing's read prices.
     *
     * @param cell   the listing the prices were read for
     * @param prices the read result ({@link ListingChannel#readMarketPrices})
     * @return true when at least one option's system price changed (D34 counts listings by this)
     */
    public boolean apply(ProductListing cell, MarketPrices prices) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        boolean esm = cell.getPlatform() == Platform.ESM;
        List<PriceHistoryRecorder.SellingPriceChange> changes = new ArrayList<>();
        List<PriceHistoryRecorder.SellingPriceChange> auctionChanges = new ArrayList<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            MarketPrices.OptionPrice read = prices.byOptionId().get(option.getId());
            if (read == null) {
                continue;
            }
            if (!positive(read.price()) || (esm && !positive(read.auctionPrice()))) {
                continue;
            }
            ProductListingOption.ProductListingOptionBuilder builder = option.toBuilder()
                    .marketPrice(read.price())
                    .marketPriceAt(now);
            if (differs(read.price(), option.getSellingPrice())) {
                builder.sellingPrice(read.price())
                        .priceSource(GeneratedContentSource.MANUAL_OVERRIDE)
                        .originalPrice(originalPrice(cell, read.price()));
                changes.add(new PriceHistoryRecorder.SellingPriceChange(
                        option, option.getSellingPrice(), read.price()));
            }
            if (esm) {
                builder.auctionMarketPrice(read.auctionPrice());
                if (differs(read.auctionPrice(), option.getAuctionSellingPrice())) {
                    builder.auctionSellingPrice(read.auctionPrice())
                            .auctionPriceSource(GeneratedContentSource.MANUAL_OVERRIDE);
                    auctionChanges.add(new PriceHistoryRecorder.SellingPriceChange(
                            option, option.getAuctionSellingPrice(), read.auctionPrice()));
                }
            }
            productListingOptionRepository.save(builder.build());
        }
        priceHistoryRecorder.recordSellingPrices(cell, changes, PriceChangeReason.MARKET_SYNC);
        priceHistoryRecorder.recordAuctionSellingPrices(cell, auctionChanges, PriceChangeReason.MARKET_SYNC);
        return !changes.isEmpty() || !auctionChanges.isEmpty();
    }

    /** D31: the display original price of the new price; null when the margin preset is missing. */
    private BigDecimal originalPrice(ProductListing cell, BigDecimal salePrice) {
        try {
            return priceCalculator.displayOriginalPrice(cell, salePrice);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean positive(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    /** compareTo, never equals — 10000 and 10000.00 are the same price. */
    private static boolean differs(BigDecimal read, BigDecimal current) {
        return current == null || read.compareTo(current) != 0;
    }
}
