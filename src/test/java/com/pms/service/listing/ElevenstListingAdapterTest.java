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
    @Mock private com.pms.repository.ListingMarketImageRepository marketImageRepository;
    @Mock private com.pms.service.elevenst.ElevenstProductImageClient productImageClient;
    @Mock private com.pms.service.elevenst.ElevenstPhotoKeyService photoKeyService;

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

    /** FEATURE_2611_12: the 1009 answer {@code fetchProduct} reads its options from. */
    private void stocks(String productStocks) {
        given(client.post(eq(ElevenstListingAdapter.STOCKS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ProductStocks><ProductStocks><prdNo>5551</prdNo>" + productStocks
                        + "</ProductStocks></ProductStocks>"));
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
                "<asDetail>A/S 글</asDetail>", "<ProductNotification><type>891045</type>",
                "<selTermUseYn>N</selTermUseYn>");
        assertThat(xml).doesNotContain("PrdFrDlvBasiAmt", "dlvCstInfoCd", "advrtStmt",
                "selPrdClfCd", "aplBgnDy", "aplEndDy");
    }

    @Test
    void buildPayload_singleActiveOption_sendsNoOptionBlock() {
        options(option(11L, "단품", "9900.00", 5, null));

        String xml = adapter.buildPayload(cell, gen);

        assertThat(xml).contains("<selPrc>9900</selPrc>", "<prdSelQty>5</prdSelQty>",
                "<selTermUseYn>N</selTermUseYn>");
        assertThat(xml).doesNotContain("<ProductOption>", "colTitle", "optSelectYn",
                "selPrdClfCd", "aplBgnDy", "aplEndDy");
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

    // ---- FEATURE_2610_27 / D16 · D30 · D37: market price read ----

    @Test
    void fetchStatus_keepsSelPrcForThePriceRead() {
        given(client.post(eq(ElevenstListingAdapter.STATUS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<Products><product><selStatCd>103</selStatCd><selPrc>9900</selPrc></product></Products>"));

        assertThat(adapter.fetchStatus(cell, null).prices().base()).isEqualByComparingTo("9900");
    }

    @Test
    void readMarketPrices_selPrcPlusAddPrc_pairedBySellerStockCd() {
        options(option(11L, "작은", "9900.00", 5, null), option(12L, "큰", "12900.00", 5, null));
        given(client.post(eq(ElevenstListingAdapter.STOCKS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ProductStocks><ProductStocks><prdNo>5551</prdNo>"
                        + "<ProductStock><addPrc>0</addPrc><sellerStockCd>11</sellerStockCd></ProductStock>"
                        + "<ProductStock><addPrc>3000</addPrc><sellerStockCd>12</sellerStockCd></ProductStock>"
                        + "<ProductStock><addPrc>500</addPrc><sellerStockCd>999</sellerStockCd></ProductStock>"
                        + "</ProductStocks></ProductStocks>"));
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of(), "판매중",
                new FetchResult.Prices(new BigDecimal("9900"), null, Map.of()));

        MarketPrices prices = adapter.readMarketPrices(cell, null, fetched);

        assertThat(prices.byOptionId()).containsOnlyKeys(11L, 12L);
        assertThat(prices.byOptionId().get(11L).price()).isEqualByComparingTo("9900");
        assertThat(prices.byOptionId().get(12L).price()).isEqualByComparingTo("12900");
    }

    @Test
    void readMarketPrices_singleActiveOptionWithoutPair_takesSelPrc() {
        ProductListingOption off = option(13L, "꺼짐", "9900.00", 1, null).toBuilder().active(false).build();
        options(option(11L, "단품", "9900.00", 5, null), off);
        given(client.post(eq(ElevenstListingAdapter.STOCKS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ProductStocks><ProductStocks><prdNo>5551</prdNo></ProductStocks></ProductStocks>"));
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of(), "판매중",
                new FetchResult.Prices(new BigDecimal("10900"), null, Map.of()));

        MarketPrices prices = adapter.readMarketPrices(cell, null, fetched);

        assertThat(prices.byOptionId()).containsOnlyKeys(11L);
        assertThat(prices.byOptionId().get(11L).price()).isEqualByComparingTo("10900");
    }
    /** FEATURE_2611_11 / D19 · D35: 1620 name + detail, public OpenAPI photos with the company key. */
    @Test
    void fetchProduct_nameAndDetailFrom1620_photosFromPublicOpenApi() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 이름</prdNm><selStatCd>103</selStatCd>"
                        + "<htmlDetail><![CDATA[<p>11번가 상세</p>]]></htmlDetail></Product>"));
        stocks("");
        given(photoKeyService.currentKey()).willReturn(java.util.Optional.of("KEY32"));
        given(productImageClient.fetch("5551", "KEY32")).willReturn(new com.pms.service.elevenst
                .ElevenstProductImageClient.Photos("http://i.011st.com/a_B.jpg", List.of("http://i.011st.com/a_A1.jpg")));

        ImportedProduct product = adapter.fetchProduct("5551", null);

        assertThat(product.productName()).isNull();
        assertThat(product.displayName()).isEqualTo("11번가 이름");
        assertThat(product.detailHtml()).isEqualTo("<p>11번가 상세</p>");
        assertThat(product.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(product.imageNotice()).isNull();
        assertThat(product.marketImages()).extracting(ImportedProduct.MarketImage::representative,
                        ImportedProduct.MarketImage::url)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, "http://i.011st.com/a_B.jpg"),
                        org.assertj.core.groups.Tuple.tuple(false, "http://i.011st.com/a_A1.jpg"));
    }

    /** FEATURE_2611_11 / D39: no company key → no photo call, the key guidance. */
    @Test
    void fetchProduct_noPhotoKey_noPhotoCall_keyGuidance() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 이름</prdNm></Product>"));
        stocks("");
        given(photoKeyService.currentKey()).willReturn(java.util.Optional.empty());

        ImportedProduct product = adapter.fetchProduct("5551", null);

        assertThat(product.marketImages()).isEmpty();
        assertThat(product.imageNotice())
                .isEqualTo("11번가 사진을 가져오지 못했습니다. [설정]에서 11번가 사진 조회 키를 넣어 주세요");
        org.mockito.Mockito.verify(productImageClient, org.mockito.Mockito.never()).fetch(anyString(), anyString());
    }

    /** FEATURE_2611_11 / D35: a failed photo read (e.g. 004 daily limit) → no photos, the plain guidance. */
    @Test
    void fetchProduct_photoReadFailed_plainGuidance() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 이름</prdNm></Product>"));
        stocks("");
        given(photoKeyService.currentKey()).willReturn(java.util.Optional.of("KEY32"));
        given(productImageClient.fetch("5551", "KEY32"))
                .willThrow(new com.pms.service.elevenst.ElevenstPhotoReadException("11st photo read error 004"));

        ImportedProduct product = adapter.fetchProduct("5551", null);

        assertThat(product.marketImages()).isEmpty();
        assertThat(product.imageNotice()).isEqualTo("11번가 사진을 가져오지 못했습니다");
    }

    /**
     * FEATURE_2611_12 / D25 · D26: 1620 gives the category and the product price, 1009 the options — id = 11st's own
     * option number, name = mixDtlOptNm, price = selPrc + addPrc, stock = stckQty.
     */
    @Test
    void fetchProduct_optionsFrom1009_marketIdPrdStckNo_priceSelPrcPlusAddPrc_categoryDispCtgrNo() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 양말</prdNm><selStatCd>103</selStatCd><selPrc>9900</selPrc>"
                        + "<dispCtgrNo>1001</dispCtgrNo></Product>"));
        stocks("<ProductStock><addPrc>0</addPrc><mixDtlOptNm>검정</mixDtlOptNm><prdStckNo>48053982676</prdStckNo>"
                + "<stckQty>7</stckQty><sellerStockCd>ABC-1</sellerStockCd></ProductStock>"
                + "<ProductStock><addPrc>3000</addPrc><mixDtlOptNm> 흰색 </mixDtlOptNm><prdStckNo>48053982677</prdStckNo>"
                + "<stckQty>0</stckQty></ProductStock>");
        given(photoKeyService.currentKey()).willReturn(java.util.Optional.empty());

        ImportedProduct product = adapter.fetchProduct("5551", null);

        assertThat(product.categoryCode()).isEqualTo("1001");
        assertThat(product.productName()).isNull();
        assertThat(product.options()).extracting(ImportedProduct.Option::itemName, ImportedProduct.Option::vendorItemId,
                        ImportedProduct.Option::stockQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("검정", "48053982676", 7),
                        org.assertj.core.groups.Tuple.tuple("흰색", "48053982677", 0));
        assertThat(product.options().get(0).salePrice()).isEqualByComparingTo("9900");
        assertThat(product.options().get(1).salePrice()).isEqualByComparingTo("12900");
        assertThat(product.options().get(0).sellerProductItemId()).isNull();
        assertThat(product.options().get(0).originalPrice()).isNull();
    }

    /** FEATURE_2611_12: a product without an option block — the stock row has no option name → the product name. */
    @Test
    void fetchProduct_stockWithoutOptionName_takesTheProductName_andSelPrc() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 단품</prdNm><selStatCd>103</selStatCd><selPrc>15000</selPrc></Product>"));
        stocks("<ProductStock><prdStckNo>48053982680</prdStckNo><stckQty>12</stckQty></ProductStock>");
        given(photoKeyService.currentKey()).willReturn(java.util.Optional.empty());

        ImportedProduct product = adapter.fetchProduct("5551", null);

        assertThat(product.options()).hasSize(1);
        assertThat(product.options().get(0).itemName()).isEqualTo("11번가 단품");
        assertThat(product.options().get(0).salePrice()).isEqualByComparingTo("15000");
    }

    /** FEATURE_2611_12: an unreadable 1009 answer is an error (502 with 11st's message), not an empty option list. */
    @Test
    void fetchProduct_optionReadFailed_throws() {
        given(client.get(eq(ElevenstListingAdapter.PRODUCT_READ_PATH + "5551"), any())).willReturn(ElevenstXml.document(
                "<Product><prdNm>11번가 이름</prdNm></Product>"));
        given(client.post(eq(ElevenstListingAdapter.STOCKS_PATH), anyString(), any()))
                .willReturn("not xml".getBytes(ElevenstXml.EUC_KR));

        assertThatThrownBy(() -> adapter.fetchProduct("5551", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageStartingWith("11번가 옵션을 읽지 못했습니다");
    }

    /** FEATURE_2611_12 / D27: 1007 with prdNm and limit — one page, no next page, no photo · brand · date. */
    @Test
    void searchProducts_1007ByName_onePage() {
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        given(client.post(eq(ElevenstListingAdapter.STATUS_PATH), body.capture(), any())).willReturn(ElevenstXml.document(
                "<ns2:products xmlns:ns2=\"http://skt.tmall.business.openapi.spring.service.client.domain/\">"
                        + "<ns2:product><prdNo>5551</prdNo><prdNm>11번가 양말</prdNm><selStatCd>103</selStatCd></ns2:product>"
                        + "<ns2:product><prdNo>5552</prdNo><prdNm>11번가 양말 2</prdNm><selStatCd>105</selStatCd></ns2:product>"
                        + "</ns2:products>"));

        ChannelProductPage page = adapter.searchProducts("양말", null, null);

        assertThat(body.getValue()).isEqualTo("<SearchProduct><prdNm>양말</prdNm><limit>100</limit></SearchProduct>");
        assertThat(page.nextToken()).isNull();
        assertThat(page.items()).extracting(ChannelProductSummary::platformProductId, ChannelProductSummary::productName,
                        ChannelProductSummary::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("5551", "11번가 양말", ListingStatus.SELLING),
                        org.assertj.core.groups.Tuple.tuple("5552", "11번가 양말 2", ListingStatus.SUSPENDED));
        assertThat(page.items().get(0).brand()).isNull();
    }

    /** FEATURE_2611_12 / D25: an imported option stores prdStckNo — the price pairs by it, not by sellerStockCd. */
    @Test
    void readMarketPrices_importedOption_pairedByPrdStckNo() {
        options(option(11L, "검정", "9900.00", 5, null).toBuilder().platformOptionId("48053982676").build(),
                option(12L, "흰색", "12900.00", 5, null).toBuilder().platformOptionId("48053982677").build());
        given(client.post(eq(ElevenstListingAdapter.STOCKS_PATH), anyString(), any())).willReturn(ElevenstXml.document(
                "<ProductStocks><ProductStocks><prdNo>5551</prdNo>"
                        + "<ProductStock><addPrc>0</addPrc><prdStckNo>48053982676</prdStckNo><sellerStockCd>A</sellerStockCd></ProductStock>"
                        + "<ProductStock><addPrc>4000</addPrc><prdStckNo>48053982677</prdStckNo></ProductStock>"
                        + "</ProductStocks></ProductStocks>"));
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of(), "판매중",
                new FetchResult.Prices(new BigDecimal("9900"), null, Map.of()));

        MarketPrices prices = adapter.readMarketPrices(cell, null, fetched);

        assertThat(prices.byOptionId()).containsOnlyKeys(11L, 12L);
        assertThat(prices.byOptionId().get(12L).price()).isEqualByComparingTo("13900");
    }

    /** FEATURE_2611_11 / D8 · D22: prdImage02 ~ 04 = the product's additional market photos in market order. */
    @Test
    void buildPayload_additionalMarketPhotos_prdImage02To04() {
        options(option(11L, "단품", "10000.00", 3, null));
        given(marketImageRepository.findByProductListingIdOrderBySortOrderAsc(100L)).willReturn(List.of(
                com.pms.domain.ListingMarketImage.builder().representative(true).imageUrl("s3/main.jpg").sortOrder(0).build(),
                com.pms.domain.ListingMarketImage.builder().representative(false).imageUrl("s3/a1.jpg").sortOrder(1).build(),
                com.pms.domain.ListingMarketImage.builder().representative(false).imageUrl("s3/a2.jpg").sortOrder(2).build()));

        String xml = adapter.buildPayload(cell, gen);

        assertThat(xml).contains("<prdImage01>https://s3/thumb.jpg</prdImage01><prdImage02>s3/a1.jpg</prdImage02>"
                + "<prdImage03>s3/a2.jpg</prdImage03>");
        assertThat(xml).doesNotContain("prdImage04", "s3/main.jpg");
    }
}
