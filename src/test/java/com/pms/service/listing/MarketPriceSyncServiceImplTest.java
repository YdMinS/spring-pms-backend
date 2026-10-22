package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** FEATURE_2610_27 / D13 ② · §2-8 row 8: one batch listing — prices only, the status is not saved. */
@ExtendWith(MockitoExtension.class)
class MarketPriceSyncServiceImplTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel adapter;
    @Mock private MarketPriceApplier marketPriceApplier;
    @InjectMocks private MarketPriceSyncServiceImpl service;

    private final ProductListing cell = ProductListing.builder().id(100L).platform(Platform.COUPANG)
            .status(ListingStatus.SUSPENDED).platformProductId("SP-1").seller(Seller.builder().id(7L).build()).build();

    @Test
    void syncOne_readsAndApplies_neverSavesTheStatus() {
        MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null).isActive(true).build();
        given(productListingRepository.findScopedById(100L)).willReturn(Optional.of(cell));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(account));
        given(resolver.resolve(Platform.COUPANG)).willReturn(adapter);
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of());
        given(adapter.fetchStatus(cell, account)).willReturn(fetched);
        MarketPrices prices = new MarketPrices(Map.of());
        given(adapter.readMarketPrices(cell, account, fetched)).willReturn(prices);
        given(marketPriceApplier.apply(cell, prices)).willReturn(true);

        assertThat(service.syncOne(100L)).isTrue();
        verify(productListingRepository, never()).save(any());
    }

    @Test
    void syncOne_inactiveAccount_throwsWithoutCallingTheMarket() {
        MarketplaceAccount inactive = MarketplaceAccountFixture.coupangStubBuilder("V1", null).isActive(false).build();
        given(productListingRepository.findScopedById(100L)).willReturn(Optional.of(cell));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.syncOne(100L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비활성 계정");
        verifyNoInteractions(resolver, marketPriceApplier);
    }
}
