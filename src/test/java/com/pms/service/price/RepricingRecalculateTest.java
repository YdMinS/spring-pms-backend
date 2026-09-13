package com.pms.service.price;

import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.response.RecalculateResult;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.ListingChannelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 재계산(FEATURE_2609_39 / 02 · PLAN D7 ①·D9·D14).
 *
 * <p>이 경로는 <b>로컬 전용</b>이다 — 공식도 이력도 새로 쓰지 않고 기존 이음매
 * {@code ListingAssetService.recalculateOptionPrices} 를 셀마다 부를 뿐이고, 마켓은 건드리지 않는다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RepricingRecalculateTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private PriceCalculator priceCalculator;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingChannelResolver channelResolver;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;

    private RepricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RepricingServiceImpl(productListingRepository, productListingOptionRepository,
                productListingProductRepository, priceCalculator, listingAssetService, channelResolver,
                marketplaceAccountRepository);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private final Seller seller = Seller.builder().id(7L).sellerName("행복상회").build();

    private ProductListing cell(Long id) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("쿠팡 셀")
                .status(ListingStatus.SELLING).platformProductId("SP-" + id).seller(seller).build();
    }

    private ProductListing givenCell(Long id) {
        ProductListing cell = cell(id);
        given(productListingRepository.findScopedById(id)).willReturn(Optional.of(cell));
        return cell;
    }

    @Test
    void testRecalculateCallsAssetSeamPerCell() {
        givenCell(1L);
        givenCell(2L);
        givenCell(3L);

        RecalculateResult result = service.recalculate(List.of(1L, 2L, 3L));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.cellCount()).isEqualTo(3);
        assertThat(result.failed()).isEmpty();
        verify(listingAssetService, times(3)).recalculateOptionPrices(any());
        // 🔴 이 엔드포인트는 네트워크를 쓰지 않는다.
        verifyNoInteractions(channelResolver);
        verifyNoInteractions(marketplaceAccountRepository);
    }

    /** D9 — 이 깃발은 콘텐츠 재심사 대기라는 뜻이다. 가격 때문에 켜면 sync 콘솔에 잘못 쌓인다. */
    @Test
    void testRecalculateDoesNotSetNeedsMarketSync() {
        ProductListing cell = givenCell(1L);

        service.recalculate(List.of(1L));

        assertThat(cell.isNeedsMarketSync()).isFalse();
        verify(productListingRepository, never()).save(any());
    }

    @Test
    void testRecalculateIsolatesFailedCell() {
        givenCell(1L);
        givenCell(2L);
        givenCell(3L);
        // 요청 순서대로 처리되므로 두 번째 호출만 던지게 한다(인자 매칭 대신 호출 순서로 고정).
        willDoNothing().willThrow(new IllegalArgumentException("마진 프리셋 없음")).willDoNothing()
                .given(listingAssetService).recalculateOptionPrices(any());

        RecalculateResult result = service.recalculate(List.of(1L, 2L, 3L));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.cellCount()).isEqualTo(3);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).listingId()).isEqualTo(2L);
        assertThat(result.failed().get(0).message()).contains("마진 프리셋 없음");
        // 1·3번째는 각자의 트랜잭션에서 이미 커밋됐다 — 되돌리지 않는다.
        verify(listingAssetService, times(3)).recalculateOptionPrices(any());
    }
}
