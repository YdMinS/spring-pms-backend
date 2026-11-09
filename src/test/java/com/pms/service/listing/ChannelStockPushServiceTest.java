package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.exception.BusinessException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2611_19 / D5 · D6 · D7 · D16 · D19 · D20 · D21 · D25: the one place that sends saved channel stocks. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelStockPushServiceTest {

    private static final Long LISTING_ID = 100L;

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel adapter;

    @InjectMocks private ChannelStockPushServiceImpl service;

    private final MasterProductOption masterOption = MasterProductOption.builder().id(5L).stockQuantity(40).build();
    private final MarketplaceAccount account =
            MarketplaceAccount.builder().id(3L).platform(Platform.LOTTEON).isActive(true).build();

    private ProductListing listing(Platform platform) {
        ProductListing listing = ProductListing.builder().id(LISTING_ID).platform(platform).name("셀")
                .platformProductId("LO555").seller(Seller.builder().id(7L).sellerName("행복상회").build())
                .masterProduct(MasterProduct.builder().id(1L).build()).build();
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption));
        return listing;
    }

    private static ProductListingOption option(long id, Integer stock, String marketId, boolean active) {
        return ProductListingOption.builder().id(id).optionName("opt" + id).stockQuantity(stock)
                .platformOptionId(marketId).active(active)
                .masterProductOption(MasterProductOption.builder().id(5L).build()).build();
    }

    private void options(ProductListingOption... options) {
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(options));
    }

    private void pushingAdapter(Platform platform) {
        given(resolver.resolveOptional(platform)).willReturn(Optional.of(adapter));
        given(adapter.pushesStock()).willReturn(true);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, platform)).willReturn(Optional.of(account));
    }

    // D5 · D25: a switched-off option and one without a market number are saved only; the value sent for a cleared
    // channel value is the master's (channel ?? master ?? 9999).
    @Test
    @SuppressWarnings("unchecked")
    void push_sendsTargets_switchedOffAndUnnumberedAreSkipped_valueIsChannelOrMaster() {
        ProductListing listing = listing(Platform.LOTTEON);
        ProductListingOption numbered = option(11L, null, "LO555_1", true);
        ProductListingOption unnumbered = option(12L, 3, null, true);
        ProductListingOption off = option(13L, 0, "LO555_3", false);
        options(numbered, unnumbered, off);
        pushingAdapter(Platform.LOTTEON);
        given(adapter.stockPushTargets(eq(listing), anyList())).willReturn(List.of(numbered));
        given(adapter.updateOptionStocks(eq(listing), anyList(), eq(account))).willReturn(Map.of());

        List<StockPushLine> lines = service.push(Map.of(LISTING_ID, List.of(11L, 12L, 13L)));

        ArgumentCaptor<List<ProductListingOption>> changed = ArgumentCaptor.forClass(List.class);
        verify(adapter).stockPushTargets(eq(listing), changed.capture());
        assertThat(changed.getValue()).extracting(ProductListingOption::getId).containsExactly(11L, 12L);
        ArgumentCaptor<List<StockPush>> pushes = ArgumentCaptor.forClass(List.class);
        verify(adapter).updateOptionStocks(eq(listing), pushes.capture(), eq(account));
        assertThat(pushes.getValue()).singleElement().satisfies(p -> {
            assertThat(p.option().getId()).isEqualTo(11L);
            assertThat(p.stock()).isEqualTo(40);
        });
        assertThat(lines).extracting(StockPushLine::optionName, StockPushLine::result).containsExactly(
                org.assertj.core.groups.Tuple.tuple("opt11", StockPushLine.Result.PUSHED),
                org.assertj.core.groups.Tuple.tuple("opt12", StockPushLine.Result.SKIPPED),
                org.assertj.core.groups.Tuple.tuple("opt13", StockPushLine.Result.SKIPPED));
        assertThat(lines.get(0).sellerName()).isEqualTo("행복상회");
        assertThat(lines.get(0).platform()).isEqualTo(Platform.LOTTEON);
    }

    // D6 · D21: a per-option refusal is that option's line only, with the market's own text.
    @Test
    void push_refusedOption_isFailedWithTheMarketText_othersPushed() {
        ProductListing listing = listing(Platform.COUPANG);
        ProductListingOption a = option(11L, 5, "V-1", true);
        ProductListingOption b = option(12L, 0, "V-2", true);
        options(a, b);
        pushingAdapter(Platform.COUPANG);
        given(adapter.stockPushTargets(eq(listing), anyList())).willReturn(List.of(a, b));
        given(adapter.updateOptionStocks(eq(listing), anyList(), eq(account)))
                .willReturn(Map.of(12L, "쿠팡 재고변경 실패: 유효하지 않은 재고수량입니다"));

        List<StockPushLine> lines = service.push(Map.of(LISTING_ID, List.of(11L, 12L)));

        assertThat(lines.get(0).result()).isEqualTo(StockPushLine.Result.PUSHED);
        assertThat(lines.get(1).result()).isEqualTo(StockPushLine.Result.FAILED);
        assertThat(lines.get(1).message()).isEqualTo("쿠팡 재고변경 실패: 유효하지 않은 재고수량입니다");
    }

    // D19 · D6: a whole-listing market throws once → every sent option is refused with that text.
    @Test
    void push_adapterThrows_everyTargetFailedWithThatText() {
        ProductListing listing = listing(Platform.ESM);
        ProductListingOption a = option(11L, 5, "11", true);
        ProductListingOption b = option(12L, 7, "12", true);
        options(a, b);
        pushingAdapter(Platform.ESM);
        given(adapter.stockPushTargets(eq(listing), anyList())).willReturn(List.of(a, b));
        given(adapter.updateOptionStocks(eq(listing), anyList(), eq(account)))
                .willThrow(new BusinessException("판매중지 상품은 수정할 수 없습니다", HttpStatus.BAD_GATEWAY));

        List<StockPushLine> lines = service.push(Map.of(LISTING_ID, List.of(11L, 12L)));

        assertThat(lines).allSatisfy(line -> {
            assertThat(line.result()).isEqualTo(StockPushLine.Result.FAILED);
            assertThat(line.message()).isEqualTo("판매중지 상품은 수정할 수 없습니다");
        });
    }

    // D7: a platform without a stock push gives no line and no market or account call.
    @Test
    void push_platformWithoutStockPush_noLines_noCalls() {
        listing(Platform.NAVER);
        options(option(11L, 5, "N-1", true));
        given(resolver.resolveOptional(Platform.NAVER)).willReturn(Optional.empty());

        assertThat(service.push(Map.of(LISTING_ID, List.of(11L)))).isEmpty();
        verify(marketplaceAccountRepository, never()).findBySeller_IdAndPlatform(any(), any());
    }

    // D21: nothing to send → the account is never looked up, and the market is never called.
    @Test
    void push_nothingToSend_noAccountLookup() {
        ProductListing listing = listing(Platform.LOTTEON);
        options(option(11L, 5, null, true));
        pushingAdapter(Platform.LOTTEON);
        given(adapter.stockPushTargets(eq(listing), anyList())).willReturn(List.of());

        List<StockPushLine> lines = service.push(Map.of(LISTING_ID, List.of(11L)));

        assertThat(lines).singleElement().extracting(StockPushLine::result).isEqualTo(StockPushLine.Result.SKIPPED);
        verify(marketplaceAccountRepository, never()).findBySeller_IdAndPlatform(any(), any());
        verify(adapter, never()).updateOptionStocks(any(), any(), any());
    }

    // D6: an inactive account refuses every sent option (the save already happened) instead of failing the request.
    @Test
    void push_inactiveAccount_everyTargetFailed() {
        ProductListing listing = listing(Platform.LOTTEON);
        ProductListingOption a = option(11L, 5, "LO555_1", true);
        options(a);
        pushingAdapter(Platform.LOTTEON);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.LOTTEON))
                .willReturn(Optional.of(account.toBuilder().isActive(false).build()));
        given(adapter.stockPushTargets(eq(listing), anyList())).willReturn(List.of(a));

        List<StockPushLine> lines = service.push(Map.of(LISTING_ID, List.of(11L)));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.result()).isEqualTo(StockPushLine.Result.FAILED);
            assertThat(line.message()).isEqualTo("비활성 계정");
        });
        verify(adapter, never()).updateOptionStocks(any(), any(), any());
    }
}
