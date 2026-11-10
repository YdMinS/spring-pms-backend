package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketImage;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ListingMarketImageRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.listing.shipping.LotteonShippingKeys;
import com.pms.service.listing.shipping.LotteonShippingResolver;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonPaths;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_24 / D11 · D16 ~ D28 · D42 · D51 ~ D54 · D59 · D61 · D67. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LotteonListingAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private LotteonApiClient client;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private LotteonShippingResolver shippingResolver;
    @Mock private TagMergeService tagMergeService;
    @Mock private ListingMarketImageRepository marketImageRepository;

    @InjectMocks private LotteonListingAdapter adapter;

    private final MarketplaceAccount account =
            MarketplaceAccountFixture.lotteonStubBuilder("KEY", "SR", "LO1").id(9L).build();
    private final MasterProduct master = MasterProduct.builder().id(1L).name("마스터")
            .categoryNoticeGroup("의류").categoryNotices(Map.of("색상", "검정")).build();
    private final ProductListing cell = ProductListing.builder()
            .id(100L).platform(Platform.LOTTEON).name("노출 이름").masterProduct(master)
            .status(ListingStatus.SELLING).platformProductId("LO555").build();
    private final GeneratedProductData gen = GeneratedProductData.builder()
            .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>detail</p>").build();

    @BeforeEach
    void setUp() throws Exception {
        given(masterChannelConfigService.resolveChannelCategory(any())).willReturn(
                new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.LOTTEON).code("BC1").build(), false));
        Map<String, String> shipping = new LinkedHashMap<>();
        shipping.put(LotteonShippingKeys.OWHP_NO, "PLO1");
        shipping.put(LotteonShippingKeys.RTRP_NO, "PLO2");
        shipping.put(LotteonShippingKeys.DV_CST_POL_NO, "DLD1");
        given(shippingResolver.resolve(cell)).willReturn(shipping);
        given(client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&filter_1=BC1", "KEY")).willReturn(JSON.readTree(
                "{\"itemList\":[{\"data\":{\"disp_list\":[{\"mall_dvs_cd\":\"EL\",\"disp_cat_id\":\"X\"},"
                        + "{\"mall_dvs_cd\":\"LTON\",\"disp_cat_id\":\"FC1\"},"
                        + "{\"mall_dvs_cd\":\"LTON\",\"disp_cat_id\":\"FC2\"}]}}]}"));
        given(tagMergeService.resolveTags(cell)).willReturn(List.of("물", "생수"));
        given(marketImageRepository.findByProductListingIdOrderBySortOrderAsc(100L)).willReturn(List.of());
    }

    private static ProductListingOption option(long id, String name, String price, String marketId, boolean active,
                                               MasterProductOption master) {
        return ProductListingOption.builder().id(id).optionName(name).sellingPrice(new BigDecimal(price))
                .stockQuantity(5).active(active).platformOptionId(marketId).masterProductOption(master).build();
    }

    private void options(ProductListingOption... options) {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(options));
    }

    private JsonNode posted(String path) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(path), body.capture(), eq("KEY"));
        return JSON.valueToTree(body.getValue()).path("spdLst").path(0);
    }

    private void answer(String path, String json) throws Exception {
        given(client.post(eq(path), any(), eq("KEY"))).willReturn(JSON.readTree(json));
    }

    @Test
    void register_sendsTheFixedCodesShippingNoticeTagsAndOneItemPerOption() throws Exception {
        MasterProductOption small = MasterProductOption.builder().id(7L).categoryAttributes(Map.of("용량", "1L")).build();
        options(option(11L, "1L", "9900", null, true, small), option(12L, "2L", "12900", null, true, null),
                option(13L, "3L", "15900", null, false, null));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(small));
        answer(LotteonPaths.REGISTER, "{\"returnCode\":\"0000\",\"data\":[{\"spdNo\":\"LO777\",\"resultCode\":\"0000\"}]}");

        String spdNo = adapter.register(cell, gen, account);

        assertThat(spdNo).isEqualTo("LO777");
        JsonNode product = posted(LotteonPaths.REGISTER);
        assertThat(product.path("trGrpCd").asText()).isEqualTo("SR");
        assertThat(product.path("trNo").asText()).isEqualTo("LO1");
        assertThat(product.path("scatNo").asText()).isEqualTo("BC1");
        assertThat(product.path("dcatLst").path(0).path("lfDcatNo").asText()).isEqualTo("FC1");
        assertThat(product.path("epdNo").asText()).isEqualTo("100");
        assertThat(product.path("spdNm").asText()).isEqualTo("노출 이름");
        assertThat(product.path("oplcCd").asText()).isEqualTo("ETC");
        assertThat(product.path("dvRgsprGrpCd").asText()).isEqualTo("GN000");
        assertThat(product.path("slEndDttm").asText()).isEqualTo("29991231235959");
        assertThat(product.path("slStrtDttm").asText()).hasSize(14);
        assertThat(product.path("owhpNo").asText()).isEqualTo("PLO1");
        assertThat(product.path("rtrpNo").asText()).isEqualTo("PLO2");
        assertThat(product.path("dvCstPolNo").asText()).isEqualTo("DLD1");
        assertThat(product.path("pdItmsInfo").path("pdItmsCd").asText()).isEqualTo("01");
        assertThat(product.path("scKwdLst")).hasSize(2);
        assertThat(product.path("sitmYn").asText()).isEqualTo("Y");
        assertThat(product.path("epnLst").path(0).path("cnts").asText()).isEqualTo("<p>detail</p>");
        JsonNode items = product.path("itmLst");
        assertThat(items).hasSize(2);
        assertThat(items.path(0).path("eitmNo").asText()).isEqualTo("11");
        assertThat(items.path(0).path("sortSeq").asInt()).isEqualTo(1);
        assertThat(items.path(0).path("itmOptLst").path(0).path("optNm").asText()).isEqualTo("용량");
        assertThat(items.path(0).path("itmOptLst").path(0).path("optVal").asText()).isEqualTo("1L");
        assertThat(items.path(1).path("slPrc").decimalValue()).isEqualByComparingTo("12900");
        assertThat(items.path(0).path("itmImgLst").path(0).path("origImgFileNm").asText())
                .isEqualTo("https://s3/thumb.jpg");
        assertThat(items.path(0).path("itmImgLst").path(0).path("rprtImgYn").asText()).isEqualTo("Y");
        assertThat(items.path(0).path("itmImgLst").path(0).path("epsrTypDtlCd").asText()).isEqualTo("IMG_SQRE");
    }

    @Test
    void register_oneOption_isAnItemWithoutOptions_andLotteonsLineTextIsTheFailure() throws Exception {
        options(option(11L, "1L", "9900", null, true, null));
        answer(LotteonPaths.REGISTER, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"1001\","
                + "\"resultMessage\":\"[원산지코드]은 필수입력입니다.\"}]}");

        assertThatThrownBy(() -> adapter.register(cell, gen, account))
                .isInstanceOf(BusinessException.class).hasMessage("[원산지코드]은 필수입력입니다.");
        JsonNode product = posted(LotteonPaths.REGISTER);
        assertThat(product.path("sitmYn").asText()).isEqualTo("N");
        assertThat(product.path("itmLst").path(0).has("itmOptLst")).isFalse();
    }

    @Test
    void register_marketThumbnail_sendsTheOptionsMarketPhotos() throws Exception {
        options(option(11L, "1L", "9900", null, true, null));
        given(marketImageRepository.findByProductListingIdOrderBySortOrderAsc(100L)).willReturn(List.of(
                ListingMarketImage.builder().productListingOptionId(11L).representative(true)
                        .marketImageType("IMG_LNTH").imageUrl("s3/main.jpg").sortOrder(0).build(),
                ListingMarketImage.builder().productListingOptionId(11L).representative(false)
                        .imageUrl("s3/a1.jpg").sortOrder(1).build()));
        answer(LotteonPaths.REGISTER, "{\"returnCode\":\"0000\",\"data\":[{\"spdNo\":\"LO7\",\"resultCode\":\"0000\"}]}");

        adapter.register(cell, gen.toBuilder().thumbnailSource(GeneratedContentSource.MARKET).build(), account);

        JsonNode images = posted(LotteonPaths.REGISTER).path("itmLst").path(0).path("itmImgLst");
        assertThat(images).hasSize(2);
        assertThat(images.path(0).path("origImgFileNm").asText()).isEqualTo("s3/main.jpg");
        assertThat(images.path(0).path("epsrTypDtlCd").asText()).isEqualTo("IMG_LNTH");
        assertThat(images.path(1).path("rprtImgYn").asText()).isEqualTo("N");
    }

    @Test
    void register_withoutShipping_isBlockedBeforeLotteon() {
        given(shippingResolver.resolve(cell)).willReturn(Map.of(LotteonShippingKeys.RTRP_NO, "PLO2"));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, gen, account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송설정 미완료 — 누락 필드: 출고지 / 배송비 정책");
    }

    @Test
    void register_noLtonDisplayCategory_isRefused() throws Exception {
        options(option(11L, "1L", "9900", null, true, null));
        given(client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&filter_1=BC1", "KEY"))
                .willReturn(JSON.readTree("{\"itemList\":[{\"data\":{\"disp_list\":[]}}]}"));

        assertThatThrownBy(() -> adapter.register(cell, gen, account))
                .hasMessage("롯데ON 이 이 카테고리에 전시카테고리를 붙여 두지 않았습니다");
        verify(client, never()).post(eq(LotteonPaths.REGISTER), any(), anyString());
    }

    @Test
    void update_sends90FieldsOnly_knownItemsWithoutNames_newItemsAdded_switchedOffItemsSoldOut() throws Exception {
        options(option(11L, "1L", "9900", "LO555_1", true, null), option(12L, "2L", "12900", null, true, null),
                option(13L, "3L", "15900", "LO555_3", false, null));
        answer(LotteonPaths.MODIFY, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"0000\"}]}");
        answer(LotteonPaths.ITEM_STATUS, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"0000\"}]}");

        adapter.update(cell, gen, account);

        JsonNode product = posted(LotteonPaths.MODIFY);
        assertThat(product.path("spdNo").asText()).isEqualTo("LO555");
        assertThat(product.path("trGrpCd").asText()).isEqualTo("SR");
        for (String absent : List.of("scatNo", "slTypCd", "pdTypCd", "ageLmtCd", "pdStatCd", "dvProcTypCd", "sitmYn",
                "scKwdLst", "epdNo", "slStrtDttm", "slEndDttm")) {
            assertThat(product.has(absent)).as(absent).isFalse();
        }
        JsonNode items = product.path("itmLst");
        assertThat(items).hasSize(2);
        assertThat(items.path(0).path("sitmNo").asText()).isEqualTo("LO555_1");
        assertThat(items.path(0).has("itmOptLst")).isFalse();
        assertThat(items.path(0).has("eitmNo")).isFalse();
        assertThat(items.path(1).path("eitmNo").asText()).isEqualTo("12");
        assertThat(items.path(1).path("itmOptLst").path(0).path("optVal").asText()).isEqualTo("2L");
        ArgumentCaptor<Object> soldOut = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(LotteonPaths.ITEM_STATUS), soldOut.capture(), eq("KEY"));
        JsonNode line = JSON.valueToTree(soldOut.getValue()).path("sitmLst").path(0);
        assertThat(line.path("sitmNo").asText()).isEqualTo("LO555_3");
        assertThat(line.path("slStatCd").asText()).isEqualTo("SOUT");
    }

    @Test
    void update_beforeApproval_isRefusedWithoutCalling() {
        assertThatThrownBy(() -> adapter.update(cell.toBuilder().status(ListingStatus.SUBMITTED).build(), gen, account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("롯데ON 승인이 끝난 뒤에 수정할 수 있습니다");
        verify(client, never()).post(anyString(), any(), anyString());
    }

    @Test
    void delete_endsTheSale() throws Exception {
        answer(LotteonPaths.PRODUCT_STATUS, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"0000\"}]}");

        adapter.delete(cell, account);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(LotteonPaths.PRODUCT_STATUS), body.capture(), eq("KEY"));
        assertThat(JSON.valueToTree(body.getValue()).path("spdLst").path(0).path("slStatCd").asText())
                .isEqualTo("END");
    }

    private static final String DETAIL = "{\"returnCode\":\"0000\",\"data\":{\"spdNo\":\"LO555\",\"spdNm\":\"롯데 상품\","
            + "\"scatNo\":\"BC1\",\"slStatCd\":\"%s\",\"sitmYn\":\"Y\",\"stkMgtYn\":\"Y\","
            + "\"scKwdLst\":[\"물\",\"\"],"
            + "\"pdAprvStatInfo\":{\"catAprvStatCd\":\"%s\",\"pdInfoAprvStatCd\":\"APRV_CMPT\","
            + "\"catGvbkRsnCnts\":\"카테고리 오류\",\"pdInfoGvbkRsnCnts\":\"\",\"fnlAprvYn\":\"%s\"},"
            + "\"pdItmsInfo\":{\"pdItmsCd\":\"01\",\"pdItmsArtlLst\":[{\"pdArtlCd\":\"0020\",\"pdArtlCnts\":\"검정\"}]},"
            + "\"epnLst\":[{\"pdEpnTypCd\":\"DSCRP\",\"cnts\":\"<img src='https://lo/d1.jpg'>\"}],"
            + "\"itmLst\":[{\"sitmNo\":\"LO555_1\",\"eitmNo\":\"11\",\"sitmNm\":\"단품1\",\"slPrc\":9900,\"stkQty\":5,"
            + "\"itmOptLst\":[{\"optNm\":\"용량\",\"optVal\":\"1L\"}],"
            + "\"itmImgLst\":[{\"epsrTypDtlCd\":\"IMG_SQRE\",\"origImgFileNm\":\"https://lo/1.jpg\",\"rprtImgYn\":\"Y\"}]},"
            + "{\"sitmNo\":\"LO555_2\",\"eitmNo\":\"seller-x\",\"slPrc\":12900,\"stkQty\":3,\"itmOptLst\":[]}]}}";

    @Test
    void fetchStatus_folds_matchesOurOptionsByEitmNoThenSitmNo_keepsPrices() throws Exception {
        options(option(11L, "1L", "9900", null, true, null), option(12L, "2L", "12900", "LO555_2", true, null));
        answer(LotteonPaths.PRODUCT_DETAIL, String.format(DETAIL, "SOUT", "APRV_CMPT", "Y"));

        FetchResult result = adapter.fetchStatus(cell, account);

        assertThat(result.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(result.statusName()).isEqualTo("품절");
        assertThat(result.options()).containsExactly(new FetchResult.OptionId("1L", "LO555_1", null),
                new FetchResult.OptionId("2L", "LO555_2", null));
        MarketPrices prices = adapter.readMarketPrices(cell, account, result);
        assertThat(prices.byOptionId().get(11L).price()).isEqualByComparingTo("9900");
        assertThat(prices.byOptionId().get(12L).price()).isEqualByComparingTo("12900");
    }

    @Test
    void fetchStatus_rejected_andTheReviewNoteUsesTheSameAnswer() throws Exception {
        options();
        answer(LotteonPaths.PRODUCT_DETAIL, String.format(DETAIL, "SALE", "GVBK", "N"));

        FetchResult result = adapter.fetchStatus(cell, account);
        ReviewNote note = adapter.fetchReviewNote(cell, account, result.statusName());

        assertThat(result.status()).isEqualTo(ListingStatus.REJECTED);
        assertThat(result.statusName()).isEqualTo("반려");
        assertThat(note).isEqualTo(ReviewNote.found("카테고리 오류"));
        verify(client).post(eq(LotteonPaths.PRODUCT_DETAIL), any(), eq("KEY"));
    }

    @Test
    void fetchStatus_notFinallyApproved_isSubmitted() throws Exception {
        options();
        answer(LotteonPaths.PRODUCT_DETAIL, String.format(DETAIL, "SALE", "APRV_WT", "N"));

        assertThat(adapter.fetchStatus(cell, account).status()).isEqualTo(ListingStatus.SUBMITTED);
    }

    @Test
    void fetchProduct_mapsTheDetailAnswer() throws Exception {
        answer(LotteonPaths.PRODUCT_DETAIL, String.format(DETAIL, "STP", "APRV_CMPT", "Y"));

        ImportedProduct product = adapter.fetchProduct("LO555", account);

        assertThat(product.productName()).isNull();
        assertThat(product.displayProductName()).isEqualTo("롯데 상품");
        assertThat(product.categoryCode()).isEqualTo("BC1");
        assertThat(product.status()).isEqualTo(ListingStatus.SUSPENDED);
        assertThat(product.tags()).containsExactly("물");
        assertThat(product.noticeGroup()).isEqualTo("의류");
        assertThat(product.detailHtml()).isEqualTo("<img src='https://lo/d1.jpg'>");
        assertThat(product.detailImages()).containsExactly("https://lo/d1.jpg");
        assertThat(product.thumbnailImages()).containsExactly("https://lo/1.jpg");
        assertThat(product.options()).extracting(ImportedProduct.Option::itemName).containsExactly("1L", "롯데 상품");
        assertThat(product.options().get(0).vendorItemId()).isEqualTo("LO555_1");
        assertThat(product.options().get(0).notices()).containsEntry("색상", "검정");
        assertThat(product.marketImages().get(0).vendorItemId()).isEqualTo("LO555_1");
        assertThat(product.marketImages().get(0).representative()).isTrue();
    }

    @Test
    void fetchProduct_failure_is502WithTheReadPrefix() {
        given(client.post(eq(LotteonPaths.PRODUCT_DETAIL), any(), eq("KEY")))
                .willThrow(new BusinessException("없는 상품", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> adapter.fetchProduct("LO9", account))
                .hasMessage("롯데ON 상품을 읽지 못했습니다: 없는 상품");
    }

    @Test
    void searchProducts_onePageOf100_byName() throws Exception {
        answer(LotteonPaths.PRODUCT_LIST, "{\"returnCode\":\"0000\",\"data\":[{\"spdNo\":\"LO1\",\"spdNm\":\"물\","
                + "\"slStatCd\":\"SALE\",\"fnlAprvYn\":\"Y\",\"regDttm\":\"20261101120000\"}]}");

        ChannelProductPage page = adapter.searchProducts("물", null, account);

        assertThat(page.nextToken()).isNull();
        assertThat(page.items()).containsExactly(
                new ChannelProductSummary("LO1", "물", null, ListingStatus.SELLING, "20261101120000"));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(LotteonPaths.PRODUCT_LIST), body.capture(), eq("KEY"));
        JsonNode request = JSON.valueToTree(body.getValue());
        assertThat(request.path("spdNm").asText()).isEqualTo("물");
        assertThat(request.path("rowsPerPage").asInt()).isEqualTo(100);
        assertThat(request.path("regStrtDttm").asText()).isEqualTo("20000101000000");
        assertThat(request.path("regEndDttm").asText()).isEqualTo("99991231235959");
    }

    // ---------------------------------------------------------------- FEATURE_2611_19: price · stock push

    /** D3 · D14: one 91 line — seller numbers, product, item, whole-won price, now → 9999-12-31; no lrtrNo / itmDcTypCd. */
    @Test
    void updateOptionPrice_sendsOne91Line_untilYear9999() throws Exception {
        ProductListingOption option = option(11L, "1L", "9900", "LO555_1", true, null).toBuilder()
                .productListing(cell).build();
        answer(LotteonPaths.ITEM_PRICE, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"0000\"}]}");

        adapter.updateOptionPrice(option, new BigDecimal("15000.00"), account);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(LotteonPaths.ITEM_PRICE), body.capture(), eq("KEY"));
        JsonNode line = JSON.valueToTree(body.getValue()).path("itmPrcLst").path(0);
        assertThat(line.path("trGrpCd").asText()).isEqualTo("SR");
        assertThat(line.path("trNo").asText()).isEqualTo("LO1");
        assertThat(line.path("spdNo").asText()).isEqualTo("LO555");
        assertThat(line.path("sitmNo").asText()).isEqualTo("LO555_1");
        assertThat(line.path("slPrc").isIntegralNumber()).isTrue();
        assertThat(line.path("slPrc").asLong()).isEqualTo(15000L);
        assertThat(line.path("hstStrtDttm").asText()).hasSize(14);
        assertThat(line.path("hstEndDttm").asText()).isEqualTo("99991231235959");
        assertThat(line.has("lrtrNo")).isFalse();
        assertThat(line.has("itmDcTypCd")).isFalse();
    }

    /** D4: a refused 91 line is thrown with LotteON's own text. */
    @Test
    void updateOptionPrice_refusedLine_throwsLotteonText() throws Exception {
        ProductListingOption option = option(11L, "1L", "9900", "LO555_1", true, null).toBuilder()
                .productListing(cell).build();
        answer(LotteonPaths.ITEM_PRICE, "{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"2001\","
                + "\"resultMessage\":\"종료일이 시작일 보다 이전 날짜일 수 없습니다\"}]}");

        assertThatThrownBy(() -> adapter.updateOptionPrice(option, new BigDecimal("15000.00"), account))
                .isInstanceOf(BusinessException.class).hasMessage("종료일이 시작일 보다 이전 날짜일 수 없습니다");
    }

    /** D5: targets = options LotteON numbered, on a product LotteON numbered. */
    @Test
    void stockPushTargets_numberedItemsOnly() {
        ProductListingOption numbered = option(11L, "1L", "9900", "LO555_1", true, null);
        ProductListingOption waiting = option(12L, "2L", "9900", null, true, null);

        assertThat(adapter.pushesStock()).isTrue();
        assertThat(adapter.stockPushTargets(cell, List.of(numbered, waiting))).containsExactly(numbered);
        assertThat(adapter.stockPushTargets(cell.toBuilder().platformProductId(null).build(), List.of(numbered)))
                .isEmpty();
    }

    /** D5 · D21: one 86 line per item; a refused line is that option's only. */
    @Test
    void updateOptionStocks_one86LinePerItem_refusalIsPerOption() throws Exception {
        ProductListingOption a = option(11L, "1L", "9900", "LO555_1", true, null);
        ProductListingOption b = option(12L, "2L", "9900", "LO555_2", true, null);
        given(client.post(eq(LotteonPaths.ITEM_STOCK), any(), eq("KEY"))).willReturn(
                JSON.readTree("{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"0000\"}]}"),
                JSON.readTree("{\"returnCode\":\"0000\",\"data\":[{\"resultCode\":\"1002\","
                        + "\"resultMessage\":\"재고수량 형식 오류\"}]}"));

        Map<Long, String> refused = adapter.updateOptionStocks(cell,
                List.of(new StockPush(a, 0), new StockPush(b, 7)), account);

        assertThat(refused).containsExactly(Map.entry(12L, "재고수량 형식 오류"));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client, org.mockito.Mockito.times(2)).post(eq(LotteonPaths.ITEM_STOCK), body.capture(), eq("KEY"));
        JsonNode first = JSON.valueToTree(body.getAllValues().get(0)).path("itmStkLst").path(0);
        assertThat(first.path("spdNo").asText()).isEqualTo("LO555");
        assertThat(first.path("sitmNo").asText()).isEqualTo("LO555_1");
        assertThat(first.path("stkQty").asInt()).isEqualTo(0);
        assertThat(first.has("lrtrNo")).isFalse();
    }
}
