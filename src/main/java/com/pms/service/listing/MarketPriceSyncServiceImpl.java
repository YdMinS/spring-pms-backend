package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MarketPriceSyncService} — status call + price read + {@link MarketPriceApplier} (FEATURE_2610_27 / D13 ②).
 * The status call is only the source of the prices here: the listing status is NOT saved (§2-8 row 8).
 */
@Service
@RequiredArgsConstructor
public class MarketPriceSyncServiceImpl implements MarketPriceSyncService {

    private final ProductListingRepository productListingRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;
    private final MarketPriceApplier marketPriceApplier;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean syncOne(Long listingId) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
        if (Boolean.FALSE.equals(acct.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        ListingChannel adapter = resolver.resolve(cell.getPlatform());
        FetchResult fetched = adapter.fetchStatus(cell, acct);
        return marketPriceApplier.apply(cell, adapter.readMarketPrices(cell, acct, fetched));
    }
}
