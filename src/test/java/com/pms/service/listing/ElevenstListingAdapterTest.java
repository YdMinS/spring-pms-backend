package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.ListingTextValueResolver;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstXml;
import com.pms.service.listing.shipping.ElevenstShippingKeys;
import com.pms.service.listing.shipping.ElevenstShippingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/** FEATURE_2610_13 / D55 · D59 · D62 · D66 · D68 · D69 · D90 · D103 · D107. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstListingAdapterTest {

    @Mock private ElevenstApiClient client;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private ElevenstShippingResolver shippingResolver;
    @Mock private ListingTextValueResolver textValueResolver;

    @InjectMocks private ElevenstListingAdapter adapter;

    private final MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
    private final ProductListing cell = ProductListing.builder()
            .id(100L).platform(Platform.ELEVENST).name("노출 이름").masterProduct(master)
            .platformProductId("5551").build();
    private final GeneratedProductData gen = GeneratedProductData.builder()
            .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>detail</p>").build();

    @BeforeEach
    void setUp() {
        given(masterChannelConfigService.resolveChannelCategory(any())).willReturn(
                new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.ELEVENST).code("1001").build(), false));
        given(textValueResolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE)).willReturn("A/S 글");
        given(textValueResolver.notice(cell, ListingTextValueResolver.KEY_RETURN_EXCHANGE_NOTICE)).willReturn("반품 글");
        given(textValueResolver.brand(cell)).willReturn("브랜드");
        Map<String, String> shipping = new LinkedHashMap<>();
        shipping.put(ElevenstShippingKeys.DLV_CN_AREA_CD, "01");
        shipping.put(ElevenstShippingKeys.DLV_WY_CD, "01");
        shipping.put(ElevenstShippingKeys.DLV_ETPRS_CD, "00034");
        shipping.put(ElevenstShippingKeys.DLV_CST_INST_BASI_CD, "02");
        shipping.put(ElevenstShippingKeys.DLV_CST1, "3000");
        shipping.put(ElevenstShippingKeys.DLV_CST_PAY_TYP_CD, "03");
        shipping.put(ElevenstShippingKeys.BNDL_DLV_CN_YN, "Y");
        shipping.put(ElevenstShippingKeys.JEJU_DLV_CST, "3000");
        shipping.put(ElevenstShippingKeys.ISLAND_DLV_CST, "5000");
        shipping.put(ElevenstShippingKeys.RTNGD_DLV_CST, "3000");
        shipping.put(ElevenstShippingKeys.EXCH_DLV_CST, "6000");
        shipping.put(ElevenstShippingKeys.ADDR_SEQ_OUT, "11");
        shipping.put(ElevenstShippingKeys.ADDR_SEQ_IN, "12");
        given(shippingResolver.resolve(cell)).willReturn(shipping);
    }

    private void options(ProductListingOption... options) {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(options));
    }

    private static ProductListingOption option(long id, String name, String price, Integer stock,
                                               MasterProductOption master) {
        return ProductListingOption.builder().id(id).optionName(name).sellingPrice(new BigDecimal(price))
                .stockQuantity(stock).active(true).masterProductOption(master).build();
    }

    @Test
    void buildPayload_twoOptions_cheapestIsBase_restCarryDifference_titleFromAttributeNames() {
        MasterProductOption red = MasterProductOption.builder().id(7L)
                .categoryAttributes(new LinkedHashMap<>(Map.of("색상", "빨강"))).build();
        MasterProductOption big = MasterProductOption.builder().id(8L)
                .categoryAttributes(new LinkedHashMap<>(Map.of("사이즈", "L"))).build();
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(red, big));
        options(option(11L, "빨강", "12000.00", 3, red), option(12L, "L", "10000.00", null, big));

        String xml = adapter.buildPayload(cell, gen);

        assertThat(xml).contains("<prdNm>노출 이름</prdNm>", "<selPrc>10000</selPrc>",
                "<prdSelQty>10002</prdSelQty>", "<colTitle>색상/사이즈</colTitle>",
                "<colOptPrice>2000</colOptPrice><colValue0>빨강</colValue0><colCount>3</colCount>"
                        + "<colSellerStockCd>11</colSellerStockCd>",
                "<colOptPrice>0</colOptPrice><colValue0>L</colValue0><colCount>9999</colCount>",
                "<dlvCst1>3000</dlvCst1>", "<dlvEtprsCd>00034</dlvEtprsCd>", "<dlvClf>02</dlvClf>",
                "<asDetail>A/S 글</asDetail>", "<ProductNotification><type>891045</type>");
        assertThat(xml).doesNotContain("PrdFrDlvBasiAmt", "dlvCstInfoCd", "advrtStmt");
    }

    @Test
    void buildPayload_singleActiveOption_sendsNoOptionBlock() {
        options(option(11L, "단품", "9900.00", 5, null));

        String xml = adapter.buildPayload(cell, gen);

        assertThat(xml).contains("<selPrc>9900</selPrc>", "<prdSelQty>5</prdSelQty>");
        assertThat(xml).doesNotContain("<ProductOption>", "colTitle", "optSelectYn");
    }

    @Test
    void validateRegistrable_blocksInDecisionOrder() {
        given(textValueResolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE)).willReturn(null);
        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, null))
                .hasMessage("A/S 안내 · 반품/교환 안내를 넣으세요");

        given(textValueResolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE)).willReturn("A/S 글");
        given(textValueResolver.brand(cell)).willReturn(null);
        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, null)).hasMessage("브랜드를 넣으세요");

        given(textValueResolver.brand(cell)).willReturn("브랜드");
        options(option(11L, "A", "10000.00", 0, null), option(12L, "B", "20010.00", 0, null));
        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, null))
                .hasMessage("11번가 규칙에 맞지 않아 올릴 수 없습니다: 켜진 옵션의 재고가 모두 0 입니다");

        options(option(11L, "A", "10000.00", 1, null), option(12L, "B", "20010.00", 1, null));
        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, null))
                .hasMessage("11번가 규칙에 맞지 않아 올릴 수 없습니다: "
                        + "가장 비싼 옵션(B 20010원)이 가장 싼 옵션 가격의 두 배를 넘습니다");

        options(option(11L, "A", "10000.00", 1, null), option(12L, "A", "10000.00", 1, null));
        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, null))
                .hasMessage("11번가 규칙에 맞지 않아 올릴 수 없습니다: 옵션 이름이 겹칩니다: A");
    }

    @Test
    void register_returnsProductNo_or_surfacesElevenstMessage() {
        options(option(11L, "단품", "9900.00", 5, null));
        given(client.post(eq(ElevenstListingAdapter.PRODUCT_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ClientMessage><message>등록되었습니다</message><productNo>777</productNo>"
                        + "<resultCode>200</resultCode></ClientMessage>"));
        assertThat(adapter.register(cell, gen, null)).isEqualTo("777");

        given(client.post(eq(ElevenstListingAdapter.PRODUCT_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ClientMessage><message>카테고리 오류</message><resultCode>500</resultCode></ClientMessage>"));
        assertThatThrownBy(() -> adapter.register(cell, gen, null))
                .isInstanceOf(BusinessException.class).hasMessage("카테고리 오류");
    }

    @Test
    void fetchStatus_selling_reportsActiveOptionsWithOurIds() {
        ProductListingOption off = option(13L, "꺼짐", "9900.00", 1, null).toBuilder().active(false).build();
        options(option(11L, "단품", "9900.00", 5, null), off);
        given(client.post(eq(ElevenstListingAdapter.STATUS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<Products><product><prdNo>5551</prdNo><selStatCd>103</selStatCd></product></Products>"));

        FetchResult result = adapter.fetchStatus(cell, null);

        assertThat(result.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(result.statusName()).isEqualTo("판매중");
        assertThat(result.options()).containsExactly(new FetchResult.OptionId("단품", "11", null));
    }

    @Test
    void fetchStatus_rejectedAndSuspended_carryNoOptions() {
        given(client.post(eq(ElevenstListingAdapter.STATUS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<Products><product><selStatCd>109</selStatCd></product></Products>"));
        assertThat(adapter.fetchStatus(cell, null).status()).isEqualTo(ListingStatus.REJECTED);

        given(client.post(eq(ElevenstListingAdapter.STATUS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<Products><product><selStatCd>108</selStatCd></product></Products>"));
        FetchResult suspended = adapter.fetchStatus(cell, null);
        assertThat(suspended.status()).isEqualTo(ListingStatus.SUSPENDED);
        assertThat(suspended.statusName()).isEqualTo("판매금지");
        assertThat(suspended.options()).isEmpty();
    }
}
