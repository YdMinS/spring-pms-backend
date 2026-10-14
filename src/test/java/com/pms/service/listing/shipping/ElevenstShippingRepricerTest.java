package com.pms.service.listing.shipping;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_13 / D117 · D120 · D121: 11st cells only · a failed cell is counted · pending only on a real change. */
@ExtendWith(MockitoExtension.class)
class ElevenstShippingRepricerTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ListingAssetService listingAssetService;
    @InjectMocks private ElevenstShippingRepricerImpl repricer;

    @BeforeEach
    void wireSelf() {
        // No Spring proxy in a unit test: the self reference is the instance itself.
        ReflectionTestUtils.setField(repricer, "self", repricer);
    }

    @Test
    void repriceMaster_skipsCoupangCells_countsFailedElevenstCells() {
        ProductListing coupang = ProductListing.builder().id(1L).platform(Platform.COUPANG).build();
        ProductListing ok = ProductListing.builder().id(2L).platform(Platform.ELEVENST).build();
        ProductListing broken = ProductListing.builder().id(3L).platform(Platform.ELEVENST).build();
        given(productListingRepository.findByMasterProductId(9L)).willReturn(List.of(coupang, ok, broken));
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(ok));
        given(productListingRepository.findScopedById(3L)).willReturn(Optional.of(broken));
        willAnswer(invocation -> {
            ProductListing cell = invocation.getArgument(0);
            if (cell.getId() == 3L) {
                throw new IllegalArgumentException("수수료 미설정 — 카테고리 시드 필요");
            }
            return null;
        }).given(listingAssetService).recalculateOptionPrices(any());

        int failed = repricer.repriceMaster(9L);

        assertThat(failed).isEqualTo(1);
        verify(listingAssetService).recalculateOptionPrices(ok);
        verify(listingAssetService, never()).recalculateOptionPrices(coupang);
    }

    @Test
    void repriceOne_marketOptionPriceChanged_raisesNeedsMarketSync() {
        ProductListing cell = ProductListing.builder().id(2L).platform(Platform.ELEVENST)
                .platformProductId("11ST-1").build();
        ProductListingOption before = ProductListingOption.builder().id(20L).active(true)
                .sellingPrice(new BigDecimal("10000")).build();
        ProductListingOption after = before.toBuilder().sellingPrice(new BigDecimal("10100")).build();
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(cell));
        given(productListingOptionRepository.findByProductListingId(2L))
                .willReturn(List.of(before), List.of(after));

        repricer.repriceOne(2L);

        ArgumentCaptor<ProductListing> saved = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(saved.capture());
        assertThat(saved.getValue().isNeedsMarketSync()).isTrue();
    }

    @Test
    void repriceOne_priceUnchanged_leavesNeedsMarketSyncOff() {
        ProductListing cell = ProductListing.builder().id(2L).platform(Platform.ELEVENST)
                .platformProductId("11ST-1").build();
        ProductListingOption option = ProductListingOption.builder().id(20L).active(true)
                .sellingPrice(new BigDecimal("10000")).build();
        given(productListingRepository.findScopedById(2L)).willReturn(Optional.of(cell));
        given(productListingOptionRepository.findByProductListingId(2L))
                .willReturn(List.of(option), List.of(option.toBuilder().sellingPrice(new BigDecimal("10000.00")).build()));

        repricer.repriceOne(2L);

        verify(productListingRepository, never()).save(any());
    }
}
