package com.pms.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.esm.EsmApiClient;
import com.pms.service.esm.EsmCategories;
import com.pms.service.esm.EsmKey;
import com.pms.service.listing.shipping.EsmShippingKeys;
import com.pms.service.listing.shipping.EsmShippingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_26 / D13 · D20 · D21 · D23 · D24 · D25 · D28 · D29 · D30 · D31 · D33 · D54. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsmListingAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private EsmApiClient client;
    @Mock private EsmCategories esmCategories;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private EsmShippingResolver shippingResolver;
    @Mock private com.pms.repository.ListingMarketImageRepository marketImageRepository;

    @InjectMocks private EsmListingAdapter adapter;

    private final MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
    private final ProductListing cell = ProductListing.builder()
            .id(100L).platform(Platform.ESM).name("노출 이름").masterProduct(master)
            .platformProductId("2103374786").status(ListingStatus.SELLING).build();
    private final GeneratedProductData gen = GeneratedProductData.builder()
            .thumbnailUrl("https://s3/thumb.jpg")
            .detailHtml("<img style=\"display:inline-block;max-width:80%;height:auto;\">").build();
    private final MarketplaceAccount account =
            MarketplaceAccountFixture.esmStubBuilder("m", "s", "g", "a").id(3L).build();
    private final EsmKey key = new EsmKey("m", "s", "g", "a");
    private final Map<String, String> shipping = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        given(masterChannelConfigService.resolveChannelCategory(any())).willReturn(
                new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.ESM).code("00070001000100000000").build(),
                        false));
        given(esmCategories.matchedLeaves(key, "00070001000100000000"))
                .willReturn(new EsmCategories.SiteLeaves("300009483", "39010100"));
        shipping.put(EsmShippingKeys.PLACE_NO, "1025418");
        shipping.put(EsmShippingKeys.BUNDLE_POLICY_NO, "4235749");
        shipping.put(EsmShippingKeys.DISPATCH_POLICY_NO, "306");
        shipping.put(EsmShippingKeys.RETURN_ADDR_NO, "1546789");
        shipping.put(EsmShippingKeys.COMPANY_NO, "10013");
        shipping.put(EsmShippingKeys.RETURN_FEE, "3000");
        given(shippingResolver.resolve(cell)).willReturn(shipping);
    }

    private ProductListingOption option(long id, String name, String gmarket, String auction, Integer stock) {
        return ProductListingOption.builder().id(id).optionName(name).active(true)
                .sellingPrice(new BigDecimal(gmarket)).auctionSellingPrice(new BigDecimal(auction))
                .stockQuantity(stock).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_sendsBothSitesPerSitePricesAndDifferences_returnsGoodsNo() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "빨강", "10000", "10500", 5), option(2, "파랑", "12000", "12500", 0)));
        given(client.post(eq("/item/v1/goods"), any(), eq(key)))
                .willReturn(JSON.readTree("{\"goodsNo\":2103374786,\"resultCode\":0}"));

        String goodsNo = adapter.register(cell, gen, account);

        assertThat(goodsNo).isEqualTo("2103374786");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/item/v1/goods"), body.capture(), eq(key));
        Map<String, Object> payload = (Map<String, Object>) body.getValue();
        assertThat(payload).doesNotContainKey("isSell");
        String json = JSON.writeValueAsString(payload);
        assertThat(json).contains("\"site\":[{\"siteType\":1,\"catCode\":\"39010100\"},{\"siteType\":2,"
                + "\"catCode\":\"300009483\"}]");
        assertThat(json).contains("\"price\":{\"Gmkt\":10000,\"Iac\":10500}");
        assertThat(json).contains("\"addAmntSite\":{\"gmkt\":2000,\"iac\":2000}");
        assertThat(json).contains("\"isSoldOut\":true");
        assertThat(json).contains("\"qty\":{\"gmkt\":1,\"iac\":1}");
        assertThat(json).contains("\"managedCode\":\"100\"");
        assertThat(json).contains("\"dispatchPolicyNo\":{\"gmkt\":306,\"iac\":306}");
        assertThat(json).contains("width:80%").doesNotContain("max-width");
        assertThat(json).contains("\"siteDiscount\":{\"gmkt\":true,\"iac\":true}");
    }

    @Test
    void validate_ratioIsCheckedPerSite_auctionNamedFirstInItsReason() {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "작은", "20000", "20000", 5), option(2, "큰", "29000", "31000", 5)));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("G마켓 · 옥션 규칙에 맞지 않아 올릴 수 없습니다: 옥션: 가장 비싼 옵션(큰 31000원)이 "
                        + "가장 싼 옵션 가격의 1.5배를 넘습니다");
    }

    @Test
    void validate_allStockZero_blocked() {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "하나", "10000", "10000", 0)));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, account))
                .hasMessage("G마켓 · 옥션 규칙에 맞지 않아 올릴 수 없습니다: 켜진 옵션의 재고가 모두 0 입니다");
    }

    @Test
    void register_withoutAuctionPairing_stopsBeforeSending() {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "하나", "10000", "10000", 5)));
        given(esmCategories.matchedLeaves(key, "00070001000100000000"))
                .willReturn(new EsmCategories.SiteLeaves("300009483", null));

        assertThatThrownBy(() -> adapter.register(cell, gen, account))
                .hasMessage("ESM 이 이 카테고리에 옥션 카테고리를 짝지어 두지 않았습니다");
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_carriesIsSell_falseOnASuspendedCell() throws Exception {
        ProductListing suspended = cell.toBuilder().status(ListingStatus.SUSPENDED).build();
        given(shippingResolver.resolve(suspended)).willReturn(shipping);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "하나", "10000", "10000", 5)));

        adapter.update(suspended, gen, account);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).put(eq("/item/v1/goods/2103374786"), body.capture(), eq(key));
        assertThat(JSON.writeValueAsString(((Map<String, Object>) body.getValue()).get("isSell")))
                .isEqualTo("{\"gmkt\":false,\"iac\":false}");
    }

    @Test
    void fetchStatus_eitherSiteSelling_isSelling_withBothSitesInTheText() throws Exception {
        given(client.get(eq("/item/v1/goods/2103374786/sell-status"), eq(key)))
                .willReturn(JSON.readTree("{\"IsSell\":{\"gmkt\":true,\"iac\":false}}"));
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(7, "하나", "10000", "10000", 5)));

        FetchResult result = adapter.fetchStatus(cell, account);

        assertThat(result.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(result.statusName()).isEqualTo("G마켓 판매중 · 옥션 판매중지");
        assertThat(result.options()).singleElement().satisfies(o -> assertThat(o.vendorItemId()).isEqualTo("7"));
    }

    @Test
    void delete_sendsBothSitesStopOnly() throws Exception {
        adapter.delete(cell, account);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).put(eq("/item/v1/goods/2103374786/sell-status"), body.capture(), eq(key));
        assertThat(JSON.writeValueAsString(body.getValue())).isEqualTo("{\"isSell\":{\"gmkt\":false,\"iac\":false}}");
    }

    // ---- FEATURE_2610_27 / D16 · D30 · D37: market price read ----

    @Test
    void fetchStatus_keepsBothSitePricesForThePriceRead() throws Exception {
        given(client.get(eq("/item/v1/goods/2103374786/sell-status"), eq(key))).willReturn(JSON.readTree(
                "{\"IsSell\":{\"gmkt\":true,\"iac\":true},"
                        + "\"itemBasicInfo\":{\"Price\":{\"gmkt\":10000.0,\"iac\":10500.0}}}"));

        FetchResult result = adapter.fetchStatus(cell, account);

        assertThat(result.prices().base()).isEqualByComparingTo("10000");
        assertThat(result.prices().auctionBase()).isEqualByComparingTo("10500");
    }

    @Test
    void readMarketPrices_sitePricePlusSiteSurcharge_pairedByManageCode_ignoringCase() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(7, "작은", "10000", "10500", 5), option(8, "큰", "12000", "12500", 5)));
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key))).willReturn(JSON.readTree(
                "{\"ItemAddtionalInfo\":{\"RecommendedOpts\":{\"Independent\":{\"Details\":["
                        + "{\"ManageCode\":\"7\",\"AddAmntSite\":{\"Gmkt\":0,\"Iac\":0}},"
                        + "{\"ManageCode\":\"8\",\"AddAmntSite\":{\"Gmkt\":2000,\"Iac\":2500}}]}}}}"));
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of(), null,
                new FetchResult.Prices(new BigDecimal("10000"), new BigDecimal("10500"), Map.of()));

        MarketPrices prices = adapter.readMarketPrices(cell, account, fetched);

        assertThat(prices.byOptionId()).containsOnlyKeys(7L, 8L);
        assertThat(prices.byOptionId().get(8L).price()).isEqualByComparingTo("12000");
        assertThat(prices.byOptionId().get(8L).auctionPrice()).isEqualByComparingTo("13000");
    }

    @Test
    void readMarketPrices_singleActiveOptionWithoutPair_takesSitePrices() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(7, "하나", "10000", "10500", 5)));
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key)))
                .willReturn(JSON.readTree("{\"itemAddtionalInfo\":{\"recommendedOpts\":{\"type\":0}}}"));
        FetchResult fetched = new FetchResult(ListingStatus.SELLING, List.of(), null,
                new FetchResult.Prices(new BigDecimal("11000"), new BigDecimal("11500"), Map.of()));

        MarketPrices prices = adapter.readMarketPrices(cell, account, fetched);

        assertThat(prices.byOptionId()).containsOnlyKeys(7L);
        assertThat(prices.byOptionId().get(7L).price()).isEqualByComparingTo("11000");
        assertThat(prices.byOptionId().get(7L).auctionPrice()).isEqualByComparingTo("11500");
    }
    /** FEATURE_2611_11 / D19 · D37: post 20 GET — one name, the main photo, the additional photos and the detail. */
    @Test
    void fetchProduct_readsNameMainAndAdditionalPhotosAndDetail() throws Exception {
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key))).willReturn(JSON.readTree(
                "{\"itemBasicInfo\":{\"goodsName\":{\"kor\":\"ESM 이름\"}},"
                        + "\"itemAddtionalInfo\":{\"images\":{\"basicImgURL\":\"https://g/main.jpg\","
                        + "\"addtionalImg1URL\":\"https://g/1.jpg\",\"addtionalImg2URL\":\"\","
                        + "\"addtionalImg3URL\":\"https://g/3.jpg\"},"
                        + "\"descriptions\":{\"kor\":{\"type\":2,\"html\":\"<p>ESM 상세</p>\"}}}}"));

        ImportedProduct product = adapter.fetchProduct("2103374786", account);

        assertThat(product.productName()).isNull();
        assertThat(product.displayName()).isEqualTo("ESM 이름");
        assertThat(product.detailHtml()).isEqualTo("<p>ESM 상세</p>");
        assertThat(product.marketImages()).extracting(ImportedProduct.MarketImage::representative,
                        ImportedProduct.MarketImage::url)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, "https://g/main.jpg"),
                        org.assertj.core.groups.Tuple.tuple(false, "https://g/1.jpg"),
                        org.assertj.core.groups.Tuple.tuple(false, "https://g/3.jpg"));
    }

    /** FEATURE_2611_11 / D8 · D22: the additional market photos go out as addtionalImg1URL … in market order. */
    @Test
    @SuppressWarnings("unchecked")
    void register_sendsAdditionalMarketPhotos() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                option(1, "하나", "10000", "10500", 5)));
        given(marketImageRepository.findByProductListingIdOrderBySortOrderAsc(100L)).willReturn(List.of(
                com.pms.domain.ListingMarketImage.builder().representative(true).imageUrl("s3/main.jpg").sortOrder(0).build(),
                com.pms.domain.ListingMarketImage.builder().representative(false).imageUrl("s3/a1.jpg").sortOrder(1).build()));
        given(client.post(eq("/item/v1/goods"), any(), eq(key)))
                .willReturn(JSON.readTree("{\"goodsNo\":2103374786,\"resultCode\":0}"));

        adapter.register(cell, gen, account);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/item/v1/goods"), body.capture(), eq(key));
        String json = JSON.writeValueAsString(body.getValue());
        assertThat(json).contains("\"images\":{\"basicImgURL\":\"https://s3/thumb.jpg\",\"addtionalImg1URL\":\"s3/a1.jpg\"}");
        assertThat(json).doesNotContain("s3/main.jpg");
    }

    // ---------------------------------------------------------------- FEATURE_2611_19 / D18 · D19 · D32 · D36 ~ D39

    /** D19: all changed options or none — every active option needs an ESM option number, and the product a goods no. */
    @Test
    void stockPushTargets_allOrNothing() {
        ProductListingOption a = option(11L, "작은", "10000", "9000", 5).toBuilder().platformOptionId("11").build();
        ProductListingOption b = option(12L, "큰", "12000", "11000", 0).toBuilder().platformOptionId("12").build();
        ProductListingOption fresh = option(13L, "새", "12000", "11000", 3);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(a, b));

        assertThat(adapter.pushesPriceByUpdate()).isTrue();
        assertThat(adapter.pushesStock()).isTrue();
        assertThat(adapter.stockPushTargets(cell, List.of(a))).containsExactly(a);
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(a, b, fresh));
        assertThat(adapter.stockPushTargets(cell, List.of(a))).isEmpty();
    }

    /** Goods GET answer whose option block has rows 11 · 12 · 99 (99 = an option switched off in oclyx). */
    private static final String GOODS_WITH_ROWS = "{\"itemAddtionalInfo\":{\"recommendedOpts\":{\"type\":1,"
            + "\"isStockManage\":true,\"independent\":{\"recommendedOptName\":{\"koreanText\":\"크기\"},\"details\":["
            + "{\"recommendedOptValue\":{\"koreanText\":\"작은\"},\"isSoldOut\":false,\"qty\":{\"gmkt\":4,\"iac\":4},"
            + "\"manageCode\":\"11\",\"addAmntSite\":{\"gmkt\":0,\"iac\":0}},"
            + "{\"recommendedOptValue\":{\"koreanText\":\"큰(마켓)\"},\"IsSoldOut\":false,\"Qty\":{\"Gmkt\":7,\"Iac\":7},"
            + "\"ManageCode\":\"12\",\"addAmntSite\":{\"gmkt\":2000,\"iac\":2500}},"
            + "{\"recommendedOptValue\":{\"koreanText\":\"꺼둔\"},\"isSoldOut\":false,\"qty\":{\"gmkt\":3,\"iac\":3},"
            + "\"manageCode\":\"99\",\"addAmntSite\":{\"gmkt\":500,\"iac\":500}}]}}}}";

    /**
     * D32 · D36: option rows on ESM → post 26 with the block as read; only the paired row's qty / isSoldOut change
     * (0 = sold out + 1, field names kept as ESM wrote them), names · surcharges · other rows as read. The local option
     * set is not read at all.
     */
    @Test
    void updateOptionStocks_optionRows_sendsTheBlockAsRead_onlyPairedStockChanged() throws Exception {
        ProductListingOption b = option(12L, "큰", "14000", "14000", 0).toBuilder().platformOptionId("12").build();
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key))).willReturn(JSON.readTree(GOODS_WITH_ROWS));

        assertThat(adapter.updateOptionStocks(cell, List.of(new StockPush(b, 0)), account)).isEmpty();

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).put(eq("/item/v1/goods/2103374786/recommended-options"), body.capture(), eq(key));
        com.fasterxml.jackson.databind.JsonNode sent = JSON.valueToTree(body.getValue());
        com.fasterxml.jackson.databind.JsonNode expected = JSON.readTree(GOODS_WITH_ROWS)
                .path("itemAddtionalInfo").path("recommendedOpts");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("independent").path("details").path(1))
                .put("IsSoldOut", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("independent").path("details").path(1)
                .path("Qty")).put("Gmkt", 1).put("Iac", 1);
        assertThat(sent).isEqualTo(expected);
        verify(productListingOptionRepository, never()).findByProductListingId(any());
    }

    /** D37: a push without a row is refused, the paired one goes out; a refused post 26 = ESM text on the paired. */
    @Test
    void updateOptionStocks_unpairedPushRefused_post26RefusalOnPairedOnly() throws Exception {
        ProductListingOption a = option(11L, "작은", "10000", "9000", 5).toBuilder().platformOptionId("11").build();
        ProductListingOption fresh = option(13L, "새", "12000", "11000", 3).toBuilder().platformOptionId("13").build();
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key))).willReturn(JSON.readTree(GOODS_WITH_ROWS));
        given(client.put(eq("/item/v1/goods/2103374786/recommended-options"), any(), eq(key)))
                .willThrow(new BusinessException("[GMKT]재고를 확인하세요", HttpStatus.BAD_GATEWAY));

        Map<Long, String> refused = adapter.updateOptionStocks(cell,
                List.of(new StockPush(a, 6), new StockPush(fresh, 3)), account);

        assertThat(refused).containsExactlyInAnyOrderEntriesOf(Map.of(
                11L, "[GMKT]재고를 확인하세요", 13L, EsmListingAdapter.OPTION_NOT_FOUND));
    }

    /** D37: no push pairs with a row → post 26 is not called. */
    @Test
    void updateOptionStocks_nonePaired_noPost26() throws Exception {
        ProductListingOption fresh = option(13L, "새", "12000", "11000", 3).toBuilder().platformOptionId("13").build();
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key))).willReturn(JSON.readTree(GOODS_WITH_ROWS));

        assertThat(adapter.updateOptionStocks(cell, List.of(new StockPush(fresh, 3)), account))
                .containsExactlyEntriesOf(Map.of(13L, EsmListingAdapter.OPTION_NOT_FOUND));
        verify(client, never()).put(any(), any(), any());
    }

    /** D36 · D39 · D28: no option rows on ESM and one active option → post 194, both sites, 0 sent as 1. */
    @Test
    void updateOptionStocks_noOptionRows_oneActiveOption_sendsPost194_zeroAsOne() throws Exception {
        ProductListingOption a = option(11L, "단품", "10000", "9000", 0).toBuilder().platformOptionId("11").build();
        ProductListingOption off = option(12L, "꺼둔", "10000", "9000", 5).toBuilder().active(false).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(a, off));
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key)))
                .willReturn(JSON.readTree("{\"itemAddtionalInfo\":{\"recommendedOpts\":{\"type\":0}}}"));

        assertThat(adapter.updateOptionStocks(cell, List.of(new StockPush(a, 0)), account)).isEmpty();

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).put(eq("/item/v1/goods/2103374786/stock"), body.capture(), eq(key));
        assertThat(JSON.valueToTree(body.getValue()).toString()).isEqualTo("{\"stock\":{\"gmkt\":1,\"iac\":1}}");
    }

    /** D39: no option rows on ESM, two active options, both sent → both refused, nothing sent. */
    @Test
    void updateOptionStocks_noOptionRows_twoActiveOptions_bothSent_bothRefused() throws Exception {
        ProductListingOption a = option(11L, "작은", "10000", "9000", 5).toBuilder().platformOptionId("11").build();
        ProductListingOption b = option(12L, "큰", "12000", "11000", 2).toBuilder().platformOptionId("12").build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(a, b));
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key)))
                .willReturn(JSON.readTree("{\"itemAddtionalInfo\":{\"recommendedOpts\":{\"type\":0}}}"));

        assertThat(adapter.updateOptionStocks(cell, List.of(new StockPush(a, 5), new StockPush(b, 2)), account))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        11L, EsmListingAdapter.OPTION_NOT_FOUND, 12L, EsmListingAdapter.OPTION_NOT_FOUND));
        verify(client, never()).put(any(), any(), any());
    }

    /**
     * D39: no option rows on ESM, two active options, only one saved → refused, nothing sent (its stock must not become
     * the single-item product's stock — that product may be the other option).
     */
    @Test
    void updateOptionStocks_noOptionRows_twoActiveOptions_oneSent_refused() throws Exception {
        ProductListingOption a = option(11L, "작은", "10000", "9000", 5).toBuilder().platformOptionId("11").build();
        ProductListingOption b = option(12L, "큰", "12000", "11000", 2).toBuilder().platformOptionId("12").build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(a, b));
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key)))
                .willReturn(JSON.readTree("{\"itemAddtionalInfo\":{\"recommendedOpts\":{\"type\":0}}}"));

        assertThat(adapter.updateOptionStocks(cell, List.of(new StockPush(b, 2)), account))
                .containsExactlyEntriesOf(Map.of(12L, EsmListingAdapter.OPTION_NOT_FOUND));
        verify(client, never()).put(any(), any(), any());
    }

    /** D38: the goods GET fails → the call throws (every push refused with that text) and nothing is sent. */
    @Test
    void updateOptionStocks_goodsReadFails_throws_nothingSent() {
        ProductListingOption a = option(11L, "작은", "10000", "9000", 5).toBuilder().platformOptionId("11").build();
        given(client.get(eq("/item/v1/goods/2103374786"), eq(key)))
                .willThrow(new BusinessException("G마켓 · 옥션 호출 실패", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> adapter.updateOptionStocks(cell, List.of(new StockPush(a, 5)), account))
                .hasMessage("G마켓 · 옥션 호출 실패");
        verify(client, never()).put(any(), any(), any());
    }
}
