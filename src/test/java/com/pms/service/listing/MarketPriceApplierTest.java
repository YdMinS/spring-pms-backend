package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.PriceCalculator;
import com.pms.service.price.PriceHistoryRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_27 / D14 · D15 · D19 · D31 · D36: storing one listing's market read. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketPriceApplierTest {

    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private PriceCalculator priceCalculator;
    @Mock private PriceHistoryRecorder priceHistoryRecorder;
    @InjectMocks private MarketPriceApplier applier;

    private static ProductListing cell(Platform platform) {
        return ProductListing.builder().id(100L).platform(platform).status(ListingStatus.SELLING)
                .seller(Seller.builder().id(7L).build()).platformProductId("P-1").build();
    }

    private static ProductListingOption option(long id, String price) {
        return ProductListingOption.builder().id(id).optionName("옵션" + id).sellingPrice(new BigDecimal(price))
                .priceSource(GeneratedContentSource.AUTO).build();
    }

    private ProductListingOption saved() {
        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void samePrice_writesMarketPriceAndTimeOnly_noHistory() {
        ProductListing cell = cell(Platform.COUPANG);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option(1L, "10000.00")));

        boolean changed = applier.apply(cell, new MarketPrices(Map.of(1L,
                new MarketPrices.OptionPrice(new BigDecimal("10000"), null))));

        assertThat(changed).isFalse();
        ProductListingOption saved = saved();
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("10000");
        assertThat(saved.getMarketPriceAt()).isNotNull();
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("10000");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
        verify(priceHistoryRecorder).recordSellingPrices(eq(cell), eq(List.of()), eq(PriceChangeReason.MARKET_SYNC));
    }

    @Test
    void differentPrice_overwritesSystemPrice_manual_originalRecomputed_history() {
        ProductListing cell = cell(Platform.COUPANG);
        ProductListingOption option = option(1L, "10000.00");
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        given(priceCalculator.displayOriginalPrice(cell, new BigDecimal("12000")))
                .willReturn(new BigDecimal("15000.00"));

        boolean changed = applier.apply(cell, new MarketPrices(Map.of(1L,
                new MarketPrices.OptionPrice(new BigDecimal("12000"), null))));

        assertThat(changed).isTrue();
        ProductListingOption saved = saved();
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("12000");
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("12000");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(saved.getOriginalPrice()).isEqualByComparingTo("15000");
        verify(priceHistoryRecorder).recordSellingPrices(eq(cell), eq(List.of(
                new PriceHistoryRecorder.SellingPriceChange(option, new BigDecimal("10000.00"), new BigDecimal("12000")))),
                eq(PriceChangeReason.MARKET_SYNC));
    }

    @Test
    void presetMissing_originalPriceCleared_priceStillUpdated() {
        ProductListing cell = cell(Platform.ELEVENST);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1L, "10000.00").toBuilder().originalPrice(new BigDecimal("13000.00")).build()));
        given(priceCalculator.displayOriginalPrice(any(), any()))
                .willThrow(new IllegalArgumentException("마진 프리셋 없음"));

        applier.apply(cell, new MarketPrices(Map.of(1L, new MarketPrices.OptionPrice(new BigDecimal("9000"), null))));

        ProductListingOption saved = saved();
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("9000");
        assertThat(saved.getOriginalPrice()).isNull();
    }

    @Test
    void esm_bothSitesRead_onlyTheChangedSiteFollows() {
        ProductListing cell = cell(Platform.ESM);
        ProductListingOption option = option(1L, "10000.00").toBuilder()
                .auctionSellingPrice(new BigDecimal("11000.00")).auctionPriceSource(GeneratedContentSource.AUTO).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));

        boolean changed = applier.apply(cell, new MarketPrices(Map.of(1L,
                new MarketPrices.OptionPrice(new BigDecimal("10000"), new BigDecimal("11500")))));

        assertThat(changed).isTrue();
        ProductListingOption saved = saved();
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("10000");
        assertThat(saved.getAuctionMarketPrice()).isEqualByComparingTo("11500");
        assertThat(saved.getAuctionSellingPrice()).isEqualByComparingTo("11500");
        assertThat(saved.getAuctionPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        verify(priceHistoryRecorder).recordAuctionSellingPrices(eq(cell), eq(List.of(
                new PriceHistoryRecorder.SellingPriceChange(option, new BigDecimal("11000.00"), new BigDecimal("11500")))),
                eq(PriceChangeReason.MARKET_SYNC));
    }

    @Test
    void esm_oneSiteMissing_wholeOptionLeftUntouched() {
        ProductListing cell = cell(Platform.ESM);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option(1L, "10000.00")
                .toBuilder().auctionSellingPrice(new BigDecimal("11000.00")).build()));

        boolean changed = applier.apply(cell, new MarketPrices(Map.of(1L,
                new MarketPrices.OptionPrice(new BigDecimal("12000"), null))));

        assertThat(changed).isFalse();
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void missingOrZeroPrice_andUnpairedOption_leftUntouched() {
        ProductListing cell = cell(Platform.COUPANG);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1L, "10000.00"), option(2L, "20000.00"), option(3L, "30000.00")));
        Map<Long, MarketPrices.OptionPrice> read = new HashMap<>();
        read.put(1L, new MarketPrices.OptionPrice(null, null));
        read.put(2L, new MarketPrices.OptionPrice(BigDecimal.ZERO, null));

        boolean changed = applier.apply(cell, new MarketPrices(read));

        assertThat(changed).isFalse();
        verify(productListingOptionRepository, never()).save(any());
        verify(priceHistoryRecorder).recordSellingPrices(eq(cell), anyList(), eq(PriceChangeReason.MARKET_SYNC));
    }
}
