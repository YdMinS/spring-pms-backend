package com.pms.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Category;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.MasterProductService;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CategoryNotice;
import com.pms.service.listing.category.CoupangCategoryMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * Coupang adapter (FEATURE_2608_06 / 3c): register returns the parsed sellerProductId; fetchStatus maps the
 * market statusName → ListingStatus and parses per-option ids. ObjectMapper is real; the HTTP client and the
 * option repo are mocked.
 */
@ExtendWith(MockitoExtension.class)
class CoupangListingAdapterTest {

    @Mock private com.pms.service.coupang.CoupangApiClient client;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private CoupangCategoryMeta metaAdapter;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private TagMergeService tagMergeService;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private MasterProductService masterProductService;
    @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangListingAdapter adapter;

    @BeforeEach
    void metaDefault() {
        // buildPayload derives the notice group map from getMeta unconditionally (61); default = empty schema so
        // register tests without notices don't NPE. The notice test overrides this with a "cat-1" group.
        lenient().when(metaAdapter.getMeta(any(), anyString()))
                .thenReturn(new CategoryMetaSchema(List.of(), List.of()));
    }

    private ProductListing cell() {
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789")
                .category(Category.builder().platformCategoryId("cat-1").build())
                .build();
    }

    private MarketplaceAccount acct() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void register_parsesSellerProductIdFromResponse() {
        lenient().when(productListingOptionRepository.findByProductListingId(100L))
                .thenReturn(List.of(ProductListingOption.builder().optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).build()));
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        // Category code now comes from the master's standard category × platform mapping (44).
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any()))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":987654321}");

        String sellerProductId = adapter.register(cell(), gen, acct());

        assertThat(sellerProductId).isEqualTo("987654321");
        // displayCategoryCode = resolvePlatformCategoryCode result (44).
        assertThat(payload.getValue()).contains("\"displayCategoryCode\":\"cat-1\"");
    }

    @Test
    void register_usesPerChannelGeneratedRegistrationNameForSellerProductName() {
        // 67: sellerProductName is always the auto-generated per-channel name (active options → generator).
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨").build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        // Generator receives the cell's active option names ("1세트") + the master options.
        given(registrationNameGenerator.generate(eq(master), eq(List.of("1세트")), any()))
                .willReturn("노브랜드 생수 x 6");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        assertThat(payload.getValue()).contains("\"sellerProductName\":\"노브랜드 생수 x 6\"");
    }

    // 47/59: attributes + notices are assembled PER item (vendorItem) = merge(master, option). Option A
    // overrides 원산지; option B has no override → master value. No payload-level attributes.
    @Test
    void register_payloadItemsCarryMergedCategoryAttributesAndNotices() throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨")
                .categoryAttributes(Map.of("원산지", "국내산"))
                .categoryNotices(Map.of("제품소재", "면 100%")).build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
        ProductListingOption optA = ProductListingOption.builder().id(1L).optionName("A")
                .sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption optB = ProductListingOption.builder().id(2L).optionName("B")
                .sellingPrice(new BigDecimal("6000")).active(true).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(optA, optB));
        // master option A overrides 원산지=수입산; option B not present in the master map → master value only.
        MasterProductOption moA = MasterProductOption.builder().name("A")
                .categoryAttributes(Map.of("원산지", "수입산")).build();
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(moA));
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        // 61: the notice group (noticeCategoryName) is derived from the category meta for this code.
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(), List.of(new CategoryNotice("제품소재", "제품소재", true, "의류"))));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("attributes").isMissingNode()).isTrue();  // moved to per-item
        JsonNode itemA = json.path("items").get(0);
        JsonNode itemB = json.path("items").get(1);
        assertThat(itemA.path("attributes").get(0).path("attributeValueName").asText()).isEqualTo("수입산");
        assertThat(itemA.path("notices").get(0).path("content").asText()).isEqualTo("면 100%");
        // 61: every notice item carries the required noticeCategoryName group.
        assertThat(itemA.path("notices").get(0).path("noticeCategoryName").asText()).isEqualTo("의류");
        assertThat(itemB.path("attributes").get(0).path("attributeValueName").asText()).isEqualTo("국내산");
    }

    // 61: a notice detail with no group mapping (unknown/legacy key) is skipped — the item carries no notices.
    @Test
    void register_noticeWithoutGroupMapping_isSkipped() throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨")
                .categoryNotices(Map.of("미매핑detail", "x")).build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
        ProductListingOption optA = ProductListingOption.builder().id(1L).optionName("A")
                .sellingPrice(new BigDecimal("6000")).active(true).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(optA));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        // Meta only knows "제품소재" → "미매핑detail" has no group → skipped.
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(), List.of(new CategoryNotice("제품소재", "제품소재", true, "의류"))));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        JsonNode itemA = objectMapper.readTree(payload.getValue()).path("items").get(0);
        assertThat(itemA.path("notices").isMissingNode()).isTrue();
    }

    // 42: register payload items[] = active options only (inactive excluded).
    @Test
    void register_payloadItems_onlyActiveOptions() {
        ProductListingOption active1 = ProductListingOption.builder().id(1L).optionName("A")
                .sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption inactive = ProductListingOption.builder().id(2L).optionName("B")
                .sellingPrice(new BigDecimal("6000")).active(false).build();
        ProductListingOption active2 = ProductListingOption.builder().id(3L).optionName("C")
                .sellingPrice(new BigDecimal("6000")).active(true).build();
        given(productListingOptionRepository.findByProductListingId(100L))
                .willReturn(List.of(active1, inactive, active2));
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        assertThat(payload.getValue()).contains("\"itemName\":\"A\"").contains("\"itemName\":\"C\"");
        assertThat(payload.getValue()).doesNotContain("\"itemName\":\"B\"");
    }

    // 63: SINGLE (1 component) → bundleType=SINGLE, item carries attributes + unitCount=1.
    @Test
    void register_single_payloadHasSingleWithAttributesAndUnitCount() throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("라벨")
                .categoryAttributes(Map.of("원산지", "국내산")).build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123").masterProduct(master).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("6개입")
                        .sellingPrice(new BigDecimal("12000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(masterProductService.isBundle(1L)).willReturn(false);   // 1 component → SINGLE
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("bundleType").asText()).isEqualTo("SINGLE");
        JsonNode item = json.path("items").get(0);
        assertThat(item.has("attributes")).isTrue();
        assertThat(item.path("unitCount").asInt()).isEqualTo(1);
    }

    // 63: AB (2+ components) → bundleType=AB, item has NO attributes key, still unitCount=1, notices unchanged.
    @Test
    void register_ab_payloadHasAbNoAttributesButUnitCountAndNotices() throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("라벨")
                .categoryAttributes(Map.of("원산지", "국내산"))
                .categoryNotices(Map.of("제품소재", "면 100%")).build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123").masterProduct(master).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("혼합구성")
                        .sellingPrice(new BigDecimal("20000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(masterProductService.isBundle(1L)).willReturn(true);   // 2+ components → AB
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        // 61: notices need a noticeCategoryName group mapping to survive the payload — stub it so the AB notice
        // is emitted (attributes are forbidden for AB, but notices are not).
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(), List.of(new CategoryNotice("제품소재", "제품소재", false, "의류"))));
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("bundleType").asText()).isEqualTo("AB");
        JsonNode item = json.path("items").get(0);
        assertThat(item.has("attributes")).isFalse();                       // AB forbids attributes
        assertThat(item.path("unitCount").asInt()).isEqualTo(1);
        assertThat(item.path("notices").get(0).path("content").asText()).isEqualTo("면 100%");
    }

    // 63: master==null (backfill transition) → SINGLE default path (isBundle(null)=false).
    @Test
    void register_masterNull_defaultsToSingle() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());   // cell() has no master

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("bundleType").asText()).isEqualTo("SINGLE");
        assertThat(json.path("items").get(0).path("unitCount").asInt()).isEqualTo(1);
    }

    // 63: validateRegistrable — AB skips the required-attribute check entirely (can't send attributes anyway).
    @Test
    void validateRegistrable_ab_skipsRequiredAttributeCheck() {
        MasterProduct master = MasterProduct.builder().id(1L).build();   // no attribute values at all
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(true);

        assertThatCode(() -> adapter.validateRegistrable(cell, null, acct()))
                .doesNotThrowAnyException();
    }

    // 63: validateRegistrable — SINGLE with a required attribute left blank → 400 (schema enforced).
    @Test
    void validateRegistrable_single_enforcesRequiredAttribute() {
        MasterProduct master = MasterProduct.builder().id(1L).build();   // no value for the required attr
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of())), List.of()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 카테고리 속성 누락");
    }

    @Test
    void fetchStatus_approved_mapsSellingAndOptionIds() {
        given(client.get(anyString(), eq(""), any())).willReturn(
                "{\"code\":\"SUCCESS\",\"data\":{\"statusName\":\"승인완료\","
                        + "\"items\":[{\"itemName\":\"1세트\",\"vendorItemId\":111,\"sellerProductItemId\":222}]}}");

        FetchResult result = adapter.fetchStatus(cell(), acct());

        assertThat(result.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(result.options()).hasSize(1);
        assertThat(result.options().get(0).optionName()).isEqualTo("1세트");
        assertThat(result.options().get(0).vendorItemId()).isEqualTo("111");
        assertThat(result.options().get(0).sellerProductItemId()).isEqualTo("222");
    }

    @Test
    void fetchStatus_underReview_mapsSubmitted() {
        given(client.get(anyString(), eq(""), any())).willReturn(
                "{\"code\":\"SUCCESS\",\"data\":{\"statusName\":\"심사중\",\"items\":[]}}");

        FetchResult result = adapter.fetchStatus(cell(), acct());

        assertThat(result.status()).isEqualTo(ListingStatus.SUBMITTED);
        assertThat(result.options()).isEmpty();
    }
}
