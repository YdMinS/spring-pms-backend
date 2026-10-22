package com.pms.service.price;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingMarginResponse;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.CustomerShippingFee;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.CellBomResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * FEATURE_2610_27 / D2 · D3 · D5 · D8 · D12 · D20 and FEATURE_2610_28 / D7 · D12 · D13 · D14 · D20 · D21 · D22. The
 * real {@link PriceCalculator} runs (a mocked one would not prove D8); only its two lookups are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingMarginServiceImplTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CellBomResolver cellBomResolver;
    @Mock private PriceCalculator priceCalculator;
    @InjectMocks private ListingMarginServiceImpl service;

    private final MasterProductOption masterOption = MasterProductOption.builder().id(9L).build();

    private ProductListing cell(Platform platform) {
        ProductListing cell = ProductListing.builder().id(100L).platform(platform).status(ListingStatus.SELLING)
                .seller(Seller.builder().id(7L).build()).platformProductId("P-1").build();
        given(productListingRepository.findScopedById(100L)).willReturn(Optional.of(cell));
        return cell;
    }

    /** commission 10% (no VAT), min margin 1,000 won. */
    private static final PriceCalculator.CellPricingBasis BASIS = new PriceCalculator.CellPricingBasis(
            new BigDecimal("0.10"), new BigDecimal("0.10"), new BigDecimal("0.20"), null, new BigDecimal("1000"), null);

    /** delivery 2,500 + box 500; breakdown / break-even run the real formula. */
    private void realFormula() {
        given(priceCalculator.resolveBasis(any(PriceCalculator.CellPricingBasis.class), any(), any()))
                .willAnswer(inv -> new PriceCalculator.PricingBasis(inv.getArgument(0),
                        new BigDecimal("2500"), new BigDecimal("500")));
        PriceCalculator real = new PriceCalculator(null, mock(MasterChannelConfigService.class), BigDecimal.ZERO);
        given(priceCalculator.breakdown(any(), any(), any())).willAnswer(inv ->
                real.breakdown(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        given(priceCalculator.breakEvenPrice(any(), any())).willAnswer(inv ->
                real.breakEvenPrice(inv.getArgument(0), inv.getArgument(1)));
    }

    private void bom(ProductListingOption option, String unitCost) {
        Product product = Product.builder().id(1L).price(new BigDecimal(unitCost)).build();
        given(cellBomResolver.forOptions(any())).willReturn(Map.of(option.getId(),
                CellBomResolver.Bom.of(List.of(new CellBomResolver.Line(1L, product, 1)))));
    }

    @Test
    void margins_judgesTheMarketPrice_andFlagsBelowTheMinimum() {
        ProductListing cell = cell(Platform.COUPANG);
        ProductListingOption option = ProductListingOption.builder().id(1L).optionName("기본")
                .masterProductOption(masterOption).sellingPrice(new BigDecimal("12000"))
                .marketPrice(new BigDecimal("10000")).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        realFormula();
        given(priceCalculator.resolveCellBasis(cell)).willReturn(BASIS);
        bom(option, "6500");

        List<ListingMarginResponse.SiteMargin> sites = service.margins(100L).options().get(0).sites();

        // 10000 − (6500 + 2500 + 500) − 1000 fee = −500
        assertThat(sites).hasSize(1);
        ListingMarginResponse.SiteMargin margin = sites.get(0);
        assertThat(margin.site()).isEqualTo("COUPANG");
        assertThat(margin.judgedPrice()).isEqualByComparingTo("10000");
        assertThat(margin.marketPriceMissing()).isFalse();
        assertThat(margin.marginAmount()).isEqualByComparingTo("-500");
        assertThat(margin.below()).isTrue();
        assertThat(margin.reason()).isNull();
    }

    @Test
    void margins_noMarketPrice_usesTheSystemPrice() {
        ProductListing cell = cell(Platform.ELEVENST);
        ProductListingOption option = ProductListingOption.builder().id(1L).optionName("기본")
                .masterProductOption(masterOption).sellingPrice(new BigDecimal("20000")).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        realFormula();
        given(priceCalculator.resolveCellBasis(cell)).willReturn(BASIS);
        bom(option, "6500");

        ListingMarginResponse.SiteMargin margin = service.margins(100L).options().get(0).sites().get(0);

        assertThat(margin.site()).isEqualTo("ELEVENST");
        assertThat(margin.marketPriceMissing()).isTrue();
        assertThat(margin.judgedPrice()).isEqualByComparingTo("20000");
        assertThat(margin.marginAmount()).isEqualByComparingTo("8500");
        assertThat(margin.below()).isFalse();
    }

    @Test
    void margins_unmappedOption_andMissingPreset_giveReasonsNotNumbers() {
        ProductListing cell = cell(Platform.ESM);
        ProductListingOption option = ProductListingOption.builder().id(1L).optionName("채널 전용")
                .sellingPrice(new BigDecimal("10000")).auctionSellingPrice(new BigDecimal("10500")).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        realFormula();
        given(priceCalculator.resolveCellBasis(cell)).willReturn(BASIS);
        given(priceCalculator.resolveAuctionCellBasis(cell)).willThrow(new IllegalArgumentException("옥션 목표 마진율 미설정"));
        given(cellBomResolver.forOptions(any())).willReturn(Map.of(1L, CellBomResolver.Bom.UNMAPPED));

        List<ListingMarginResponse.SiteMargin> sites = service.margins(100L).options().get(0).sites();

        assertThat(sites).extracting(ListingMarginResponse.SiteMargin::site).containsExactly("GMARKET", "AUCTION");
        assertThat(sites.get(0).reason()).isEqualTo(ListingMarginServiceImpl.UNMAPPED_REASON);
        assertThat(sites.get(0).marginAmount()).isNull();
        assertThat(sites.get(0).components()).isEmpty();
        assertThat(sites.get(1).reason()).isEqualTo("옥션 목표 마진율 미설정");
        assertThat(sites.get(1).judgedPrice()).isEqualByComparingTo("10500");
    }

    @Test
    void margins_splitsTheFee_listsTheComponents_andCarriesTheMinimums() {
        ProductListing cell = cell(Platform.ESM);
        LocalDateTime checkedAt = LocalDateTime.of(2026, 10, 22, 2, 5);
        ProductListingOption option = ProductListingOption.builder().id(1L).optionName("기본")
                .masterProductOption(masterOption).sellingPrice(new BigDecimal("20000"))
                .auctionSellingPrice(new BigDecimal("21000")).marketPriceAt(checkedAt).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        realFormula();
        // buyer pays 3,000 won shipping → delivery-fee commission 3,000 × 3.3% = 99 won
        PriceCalculator.CellPricingBasis withShippingFee = new PriceCalculator.CellPricingBasis(
                new BigDecimal("0.10"), new BigDecimal("0.11"), new BigDecimal("0.20"), null,
                new BigDecimal("1000"), new BigDecimal("0.05"), new CustomerShippingFee(new BigDecimal("3000"), null));
        given(priceCalculator.resolveCellBasis(cell)).willReturn(withShippingFee);
        given(priceCalculator.resolveAuctionCellBasis(cell)).willReturn(withShippingFee);
        Product priced = Product.builder().id(1L).productName("사과").price(new BigDecimal("3000")).build();
        Product unpriced = Product.builder().id(2L).productName("배").build();
        given(cellBomResolver.forOptions(any())).willReturn(Map.of(1L, CellBomResolver.Bom.of(List.of(
                new CellBomResolver.Line(1L, priced, 2), new CellBomResolver.Line(2L, unpriced, 1)))));

        List<ListingMarginResponse.SiteMargin> sites = service.margins(100L).options().get(0).sites();
        ListingMarginResponse.SiteMargin gmarket = sites.get(0);

        // 20000 × 0.11 = 2200 sale commission + 99 delivery-fee commission = 2299 fee
        assertThat(gmarket.commissionRate()).isEqualByComparingTo("0.11");
        assertThat(gmarket.commissionAmount()).isEqualByComparingTo("2200");
        assertThat(gmarket.shippingFeeCommission()).isEqualByComparingTo("99");
        assertThat(gmarket.feeAmount()).isEqualByComparingTo("2299");
        // D14: the unpriced component is listed with a null amount and left out of costSum (6000)
        assertThat(gmarket.costSum()).isEqualByComparingTo("6000");
        assertThat(gmarket.components()).extracting(ListingMarginResponse.CostComponent::productName)
                .containsExactly("사과", "배");
        assertThat(gmarket.components().get(0).quantity()).isEqualTo(2);
        assertThat(gmarket.components().get(0).amount()).isEqualByComparingTo("6000");
        assertThat(gmarket.components().get(1).unitPrice()).isNull();
        assertThat(gmarket.components().get(1).amount()).isNull();
        assertThat(gmarket.minMarginAmount()).isEqualByComparingTo("1000");
        assertThat(gmarket.minMarginRate()).isEqualByComparingTo("0.05");
        // D22: both sites carry the option's one check time
        assertThat(sites).extracting(ListingMarginResponse.SiteMargin::marketPriceAt)
                .containsExactly(checkedAt, checkedAt);
        assertThat(sites.get(1).judgedPrice()).isEqualByComparingTo("21000");
    }

    @Test
    void preview_judgesTheTypedPrice() {
        ProductListing cell = cell(Platform.COUPANG);
        ProductListingOption option = ProductListingOption.builder().id(1L).optionName("기본")
                .masterProductOption(masterOption).sellingPrice(new BigDecimal("12000")).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(option));
        realFormula();
        given(priceCalculator.resolveCellBasis(cell)).willReturn(BASIS);
        bom(option, "6500");

        ListingMarginResponse.SiteMargin margin = service.preview(100L, 1L, new BigDecimal("15000"), "COUPANG");

        assertThat(margin.site()).isEqualTo("COUPANG");
        assertThat(margin.marketPriceAt()).isNull();
        assertThat(margin.judgedPrice()).isEqualByComparingTo("15000");
        assertThat(margin.marginAmount()).isEqualByComparingTo("4000");
    }

    @Test
    void preview_foreignOption_or_foreignSite_is400() {
        cell(Platform.COUPANG);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of());

        assertThatThrownBy(() -> service.preview(100L, 1L, new BigDecimal("15000"), "COUPANG"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("리스팅 옵션 아님");
        assertThatThrownBy(() -> service.preview(100L, 1L, new BigDecimal("15000"), "AUCTION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 판매상품에 없는 쇼핑몰입니다: AUCTION");
    }
}
