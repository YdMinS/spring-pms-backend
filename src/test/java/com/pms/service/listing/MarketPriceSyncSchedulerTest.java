package com.pms.service.listing;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_27 / D23 · D24 · D34: one tenant's batch counts and the tenant loop. */
@ExtendWith(MockitoExtension.class)
class MarketPriceSyncSchedulerTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private MarketPriceSyncService marketPriceSyncService;
    @InjectMocks private MarketPriceSyncScheduler scheduler;

    private static ProductListing cell(long id, long sellerId, Platform platform) {
        return ProductListing.builder().id(id).platform(platform).platformProductId("P-" + id)
                .seller(Seller.builder().id(sellerId).build()).build();
    }

    @Test
    void runTenant_countsInListings_rateLimitSkipsTheRestOfThatAccountOnly() {
        given(productListingRepository.findMarketPriceTargets()).willReturn(List.of(
                cell(1L, 7L, Platform.COUPANG), cell(2L, 7L, Platform.COUPANG), cell(3L, 7L, Platform.COUPANG),
                cell(4L, 8L, Platform.ELEVENST), cell(5L, 8L, Platform.ELEVENST)));
        given(marketPriceSyncService.syncOne(1L)).willReturn(true);
        given(marketPriceSyncService.syncOne(2L)).willThrow(new CoupangRateLimitedException(Instant.now()));
        given(marketPriceSyncService.syncOne(4L)).willThrow(new IllegalStateException("11번가 응답 오류"));
        given(marketPriceSyncService.syncOne(5L)).willReturn(false);

        MarketPriceSyncScheduler.Counts counts = scheduler.runTenant();

        assertThat(counts).isEqualTo(new MarketPriceSyncScheduler.Counts(2, 1, 2, 1));
        verify(marketPriceSyncService, never()).syncOne(3L);
    }

    @Test
    void runAll_aFailingTenantDoesNotStopTheNext() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L, 2L));
        given(productListingRepository.findMarketPriceTargets())
                .willThrow(new IllegalStateException("db down"))
                .willReturn(List.of());

        scheduler.runAll();

        verify(productListingRepository, times(2)).findMarketPriceTargets();
    }
}
