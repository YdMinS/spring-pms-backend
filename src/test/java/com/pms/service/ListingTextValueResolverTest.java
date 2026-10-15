package com.pms.service;

import com.pms.domain.ElevenstChannelDefault;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.ElevenstChannelDefaultRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.listing.CellBomResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 11st notice resolution order (FEATURE_2610_16 / D5): listing → master → the 11st sales channel default. */
@ExtendWith(MockitoExtension.class)
class ListingTextValueResolverTest {

    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CellBomResolver cellBomResolver;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ElevenstChannelDefaultRepository elevenstChannelDefaultRepository;
    @InjectMocks private ListingTextValueResolver resolver;

    private ProductListing cell(Map<String, String> masterFieldValues) {
        MasterProduct master = MasterProduct.builder().id(5L).fieldValues(masterFieldValues).build();
        return ProductListing.builder().id(100L).platform(Platform.ELEVENST)
                .seller(Seller.builder().id(7L).sellerName("셀러").build()).masterProduct(master).build();
    }

    @Test
    void notice_masterValue_winsOverSalesChannelDefault() {
        ProductListing cell = cell(Map.of(ListingTextValueResolver.KEY_AS_NOTICE, "마스터 A/S"));

        assertThat(resolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE)).isEqualTo("마스터 A/S");
        verify(marketplaceAccountRepository, never()).findBySeller_IdAndPlatform(any(), any());
    }

    @Test
    void notice_blankListingAndMaster_readsSalesChannelDefault() {
        ProductListing cell = cell(Map.of(ListingTextValueResolver.KEY_AS_NOTICE, "  "));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.ELEVENST))
                .willReturn(Optional.of(MarketplaceAccount.builder().id(60L).platform(Platform.ELEVENST).build()));
        given(elevenstChannelDefaultRepository.findByMarketplaceAccountId(60L))
                .willReturn(Optional.of(ElevenstChannelDefault.builder()
                        .asNotice("채널 A/S").returnExchangeNotice("채널 반품").build()));

        assertThat(resolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE)).isEqualTo("채널 A/S");
        assertThat(resolver.notice(cell, ListingTextValueResolver.KEY_RETURN_EXCHANGE_NOTICE)).isEqualTo("채널 반품");
    }

    @Test
    void notice_allThreeBlank_returnsNull() {
        ProductListing cell = cell(null);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.ELEVENST))
                .willReturn(Optional.of(MarketplaceAccount.builder().id(60L).platform(Platform.ELEVENST).build()));
        given(elevenstChannelDefaultRepository.findByMarketplaceAccountId(60L)).willReturn(Optional.empty());

        assertThat(resolver.notice(cell, ListingTextValueResolver.KEY_RETURN_EXCHANGE_NOTICE)).isNull();
    }
}
