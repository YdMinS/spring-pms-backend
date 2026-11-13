package com.pms.migration;

import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.listing.ListingMarketValueService;
import com.pms.service.listing.ListingMarketValueService.BackfillOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The one-off market-value backfill runner (FEATURE_2611_27 / D21 · D32 · R14). */
@ExtendWith(MockitoExtension.class)
class MarketValueBackfillRunnerTest {

    @Mock
    private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock
    private ListingMarketValueService listingMarketValueService;

    @Test
    void run_disabled_doesNothing() {
        new MarketValueBackfillRunner(marketplaceAccountRepository, listingMarketValueService, false, List.of(10L))
                .run(null);

        verifyNoInteractions(marketplaceAccountRepository, listingMarketValueService);
    }

    @Test
    void run_noListingIds_doesNothing() {
        new MarketValueBackfillRunner(marketplaceAccountRepository, listingMarketValueService, true, List.of())
                .run(null);

        verifyNoInteractions(listingMarketValueService);
    }

    @Test
    void run_enabled_triesEachIdUntilFound_failureSkipsOnlyThatId() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L, 2L));
        given(listingMarketValueService.backfillFromMarket(10L)).willReturn(BackfillOutcome.FILLED);
        given(listingMarketValueService.backfillFromMarket(20L)).willThrow(new IllegalArgumentException("비활성 계정"));
        given(listingMarketValueService.backfillFromMarket(30L)).willReturn(BackfillOutcome.NOT_FOUND);

        new MarketValueBackfillRunner(marketplaceAccountRepository, listingMarketValueService, true,
                List.of(10L, 20L, 30L)).run(null);

        verify(listingMarketValueService, times(1)).backfillFromMarket(10L);
        verify(listingMarketValueService, times(1)).backfillFromMarket(20L);
        verify(listingMarketValueService, times(2)).backfillFromMarket(30L);
        assertThat(TenantContext.get()).isNull();
    }
}
