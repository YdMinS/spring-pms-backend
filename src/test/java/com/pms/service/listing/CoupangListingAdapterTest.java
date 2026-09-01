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
import com.pms.service.listing.shipping.ResolvedShippingConfig;
import com.pms.service.listing.shipping.ShippingConfigResolver;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    @Mock private com.pms.service.OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private MasterProductService masterProductService;
    @Mock private ShippingConfigResolver shippingConfigResolver;
    @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangListingAdapter adapter;

    @BeforeEach
    void metaDefault() {
        // buildPayload derives the notice group map from getMeta unconditionally (61); default = empty schema so
        // register tests without notices don't NPE. The notice test overrides this with a "cat-1" group.
        lenient().when(metaAdapter.getMeta(any(), anyString()))
                .thenReturn(new CategoryMetaSchema(List.of(), List.of()));
        // 73/75: buildPayload requires the resolved shipping config; default = a fully-populated one so register
        // tests pass. The "배송설정 미완료" tests override this with an all-null / partial resolved config.
        lenient().when(shippingConfigResolver.resolve(any())).thenReturn(fullResolved());
    }

    private ProductListing cell() {
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789")
                .category(Category.builder().platformCategoryId("cat-1").build())
                .build();
    }

    private MarketplaceAccount acct() {
        // 73: vendorUserId (WING login id) is register-required.
        return MarketplaceAccount.builder().vendorId("V1").vendorUserId("wing-user")
                .accessKey("ak").secretKey("sk").isActive(true).build();
    }

    /** A fully-populated resolved shipping config (75) — no missing required field. extraInfoMessage null. */
    private ResolvedShippingConfig fullResolved() {
        return new ResolvedShippingConfig(
                "OUT-1",
                "RC-1", "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", new BigDecimal("0"), null, "N", "NOT_UNION_DELIVERY",
                null);
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
        given(registrationNameGenerator.generate(eq(master), eq(List.of("1세트")), any(), any()))
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
        // 96 ⑨: AB no longer returns before the meta lookup (it still has to validate notices) → the category
        // code and the schema must be reachable.
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of(), null)), List.of()));

        assertThatCode(() -> adapter.validateRegistrable(cell, null, acct()))
                .doesNotThrowAnyException();
    }

    // 63: validateRegistrable — SINGLE with a required attribute left blank → 400 (schema enforced).
    @Test
    void validateRegistrable_single_enforcesRequiredAttribute() {
        // 93: carries an attribute value unrelated to the schema → the "at least one attribute" guard passes and
        // only the required-attribute guard can fire (the two guards stay distinguishable by message).
        MasterProduct master = MasterProduct.builder().id(1L)
                .categoryAttributes(Map.of("색상", "흰색")).build();   // no value for the required attr
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of(), null)), List.of()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 카테고리 속성 누락");
    }

    // ⑤: 같은 groupNumber 의 MANDATORY 속성은 **그룹 중 하나만** 채우면 통과한다.
    // 실측 차단(2026-08-30): `최소 중량`·`최소 용량` 이 둘 다 MANDATORY + groupNumber "1" 인데 프론트는
    // 중량/용량을 택1 로 받으므로, 개별 검사하면 어떤 상품도 등록할 수 없었다.
    @Test
    void validateRegistrable_requiredAttributeGroup_satisfiedByOneMember() {
        MasterProduct master = MasterProduct.builder().id(1L)
                .categoryAttributes(Map.of("최소 중량", "100g")).build();   // 용량은 비어 있음
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("최소 중량", true, "NUMBER", List.of(), "g", "1"),
                        new CategoryAttribute("최소 용량", true, "NUMBER", List.of(), "ml", "1")),
                List.of()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));

        assertThatCode(() -> adapter.validateRegistrable(cell, null, acct()))
                .doesNotThrowAnyException();
    }

    // ⑤: 그룹 전체가 비면 여전히 막는다(완화가 "검사 삭제"가 아님). 메시지는 그룹 구성원을 모두 알려준다.
    @Test
    void validateRegistrable_requiredAttributeGroup_blocksWhenWholeGroupBlank() {
        MasterProduct master = MasterProduct.builder().id(1L)
                .categoryAttributes(Map.of("색상", "흰색")).build();   // 그룹 두 칸 모두 비어 있음
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("최소 중량", true, "NUMBER", List.of(), "g", "1"),
                        new CategoryAttribute("최소 용량", true, "NUMBER", List.of(), "ml", "1")),
                List.of()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 중량 또는 최소 용량 중 하나");
    }

    // 73: top-level required fields (sale period, vendorUserId, shipping/return/outbound block, requested=true)
    // + item defaults (originalPrice, maximumBuyCount, adultOnly …). images/searchTags/contents at item level.
    @Test
    void register_payloadCarriesRequiredTopLevelAndItemDefaults() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("10000")).originalPrice(new BigDecimal("12500"))
                        .active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        // top-level
        assertThat(json.path("requested").asBoolean()).isTrue();   // 108/D1: approval request is the default
        assertThat(json.path("saleEndedAt").asText()).isEqualTo("2099-12-31T23:59:59");
        assertThat(json.has("saleStartedAt")).isTrue();
        assertThat(json.path("vendorUserId").asText()).isEqualTo("wing-user");
        assertThat(json.path("returnCenterCode").asText()).isEqualTo("RC-1");
        assertThat(json.path("outboundShippingPlaceCode").asText()).isEqualTo("OUT-1");
        assertThat(json.path("companyContactNumber").asText()).isEqualTo("021234567");
        assertThat(json.path("remoteAreaDeliverable").asText()).isEqualTo("N");
        // images/searchTags/contents moved off top-level → item level
        assertThat(json.path("images").isMissingNode()).isTrue();
        assertThat(json.path("searchTags").isMissingNode()).isTrue();
        // item defaults
        JsonNode item = json.path("items").get(0);
        assertThat(item.path("originalPrice").asInt()).isEqualTo(12500);
        assertThat(item.path("maximumBuyCount").asInt()).isEqualTo(9999);
        assertThat(item.path("adultOnly").asText()).isEqualTo("EVERYONE");
        assertThat(item.path("taxType").asText()).isEqualTo("TAX");
        assertThat(item.path("parallelImported").asText()).isEqualTo("NOT_PARALLEL_IMPORTED");
        assertThat(item.path("pccNeeded").asBoolean()).isFalse();
        assertThat(item.path("images").get(0).path("imageType").asText()).isEqualTo("REPRESENTATION");
        // 93: contents is an array of {contentsType, contentDetails[{content, detailType}]} — not a raw String.
        assertThat(item.path("contents").isArray()).isTrue();
        assertThat(item.path("contents").get(0).path("contentsType").asText()).isEqualTo("TEXT");
        JsonNode contentDetail = item.path("contents").get(0).path("contentDetails").get(0);
        assertThat(contentDetail.path("content").asText()).isEqualTo("<p>셀</p>");
        assertThat(contentDetail.path("detailType").asText()).isEqualTo("TEXT");
        // 93: certifications is required → NOT_REQUIRED sentinel on every item.
        assertThat(item.path("certifications").get(0).path("certificationType").asText())
                .isEqualTo("NOT_REQUIRED");
        assertThat(item.path("certifications").get(0).path("certificationCode").asText()).isEmpty();
    }

    // 73: originalPrice null on the option → falls back to salePrice.
    @Test
    void register_itemOriginalPriceNull_fallsBackToSalePrice() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("10000")).active(true).build()));   // no originalPrice
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        JsonNode item = objectMapper.readTree(payload.getValue()).path("items").get(0);
        assertThat(item.path("originalPrice").asInt()).isEqualTo(10000);
    }

    // 73/75: nothing resolved (no account config, no overrides) → required fields null → 400 before the push.
    @Test
    void register_missingShippingConfig_throws400() {
        given(shippingConfigResolver.resolve(any())).willReturn(
                new ResolvedShippingConfig(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();

        assertThatThrownBy(() -> adapter.register(cell(), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("배송설정");
    }

    // 73: a required shipping field left null (after resolution) → 400 naming the missing field.
    @Test
    void register_shippingConfigMissingRequiredField_throws400() {
        given(shippingConfigResolver.resolve(any())).willReturn(new ResolvedShippingConfig(
                "OUT-1", null, "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", new BigDecimal("0"), null, "N", "NOT_UNION_DELIVERY", null));
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();

        assertThatThrownBy(() -> adapter.register(cell(), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("배송설정")
                .hasMessageContaining("returnCenterCode");
    }

    // 75: extraInfoMessage attached to the top-level payload only when the resolved value is non-blank.
    @Test
    void register_extraInfoMessage_attachedWhenPresent() throws Exception {
        given(shippingConfigResolver.resolve(any())).willReturn(new ResolvedShippingConfig(
                "OUT-1", "RC-1", "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", new BigDecimal("0"), null, "N", "NOT_UNION_DELIVERY",
                "설치배송입니다"));
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        assertThat(objectMapper.readTree(payload.getValue()).path("extraInfoMessage").asText())
                .isEqualTo("설치배송입니다");
    }

    // 75: extraInfoMessage null/blank → key absent from the payload (optional).
    @Test
    void register_extraInfoMessage_absentWhenBlank() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());   // fullResolved() has extraInfoMessage null

        assertThat(objectMapper.readTree(payload.getValue()).path("extraInfoMessage").isMissingNode()).isTrue();
    }

    // 75: 묶음배송(UNION_DELIVERY) + 착불(CHARGE_RECEIVED) at once → Coupang forbids → 400 before the push.
    @Test
    void register_unionDeliveryWithChargeReceived_throws400() {
        given(shippingConfigResolver.resolve(any())).willReturn(new ResolvedShippingConfig(
                "OUT-1", "RC-1", "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "CHARGE_RECEIVED", new BigDecimal("0"), null, "N", "UNION_DELIVERY", null));
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();

        assertThatThrownBy(() -> adapter.register(cell(), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("묶음배송");
    }

    // 73: vendorUserId unset on the account → 400 before the HTTP push.
    @Test
    void register_missingVendorUserId_throws400() {
        MarketplaceAccount noUser = MarketplaceAccount.builder().vendorId("V1")
                .accessKey("ak").secretKey("sk").isActive(true).build();   // no vendorUserId
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();

        assertThatThrownBy(() -> adapter.register(cell(), gen, noUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendorUserId");
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

    // 77: read-only mirror of requireShippingConfig — same rules, never throws.
    @Test
    void isShippingReady_completeConfig_returnsTrue() {
        given(shippingConfigResolver.resolve(any())).willReturn(fullResolved());

        assertThat(adapter.isShippingReady(cell())).isTrue();
    }

    @Test
    void isShippingReady_missingRequiredField_returnsFalseWithoutThrowing() {
        given(shippingConfigResolver.resolve(any())).willReturn(new ResolvedShippingConfig(
                "OUT-1", null, "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", new BigDecimal("0"), null, "N", "NOT_UNION_DELIVERY", null));

        assertThat(adapter.isShippingReady(cell())).isFalse();
    }

    // 93: contents is required → a cell whose detail HTML was never generated fails BEFORE any HTTP call.
    @Test
    void register_blankDetailHtml_throws() {
        // the @BeforeEach getMeta stub matches anyString() → the category code must be non-null to reach the guard
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").build();   // no detailHtml

        assertThatThrownBy(() -> adapter.register(cell(), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상세 HTML");
        verify(client, never()).post(any(), any(), any());
    }

    // 93: Coupang requires at least one attribute per item. Schema defines an OPTIONAL attribute (so the
    // empty-schema early return does not apply) and nothing is filled in → 400, distinct from the required-
    // attribute message.
    @Test
    void validateRegistrable_attributesEmpty_throws() {
        MasterProduct master = MasterProduct.builder().id(1L).build();   // no attribute values at all
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("사이즈", false, "TEXT", List.of(), null)), List.of()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("카테고리 속성 미입력");
    }

    // 93: sellerProductName over Coupang's 100-char cap is hard-cut in the adapter (the generator keeps the
    // full name for the UI).
    @Test
    void register_longName_truncatedTo100() throws Exception {
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
        String longName = "가".repeat(120);
        given(registrationNameGenerator.generate(eq(master), eq(List.of("1세트")), any(), any()))
                .willReturn(longName);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        String sent = objectMapper.readTree(payload.getValue()).path("sellerProductName").asText();
        assertThat(sent).hasSize(100);
        assertThat(sent).isEqualTo(longName.substring(0, 100));
    }

    // ── 96 ④: attribute values carry the category's 기본 단위 ────────────────────────────────────────────

    @Test
    void register_bareNumericAttribute_getsBasicUnitAppended() throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨")
                .categoryAttributes(new java.util.LinkedHashMap<>(Map.of(
                        "개당 중량", "100",        // bare number + unit in the schema  → "100g"
                        "개당 용량", "500ml",      // already carries a unit             → untouched
                        "원산지", "국내산")))       // no basicUnit in the schema         → untouched
                .build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("개당 중량", true, "NUMBER", List.of(), "g"),
                        new CategoryAttribute("개당 용량", true, "NUMBER", List.of(), "ml"),
                        new CategoryAttribute("원산지", false, "TEXT", List.of(), null)),
                List.of()));
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        Map<String, String> sent = sentAttributes(payload.getValue());
        assertThat(sent).containsEntry("개당 중량", "100g")      // unit appended at send time
                .containsEntry("개당 용량", "500ml")             // user's own unit not overwritten
                .containsEntry("원산지", "국내산");               // no basicUnit → verbatim
    }

    /** items[0].attributes[] → {attributeTypeName: attributeValueName}. */
    private Map<String, String> sentAttributes(String payload) throws Exception {
        Map<String, String> sent = new java.util.LinkedHashMap<>();
        for (JsonNode attribute : objectMapper.readTree(payload).path("items").get(0).path("attributes")) {
            sent.put(attribute.path("attributeTypeName").asText(),
                    attribute.path("attributeValueName").asText());
        }
        return sent;
    }

    // ── 96 ⑩: the notice group map is narrowed to the 품목군 the user picked ──────────────────────────────

    /** Two 품목군 sharing a notice key — 농수축산물 comes first in the schema. */
    private CategoryMetaSchema sharedNoticeKeySchema() {
        return new CategoryMetaSchema(List.of(), List.of(
                new CategoryNotice("소비기한", "소비기한", true, "농수축산물"),
                new CategoryNotice("소비기한", "소비기한", true, "가공식품"),
                new CategoryNotice("포장단위별 용량", "포장단위별 용량", true, "가공식품")));
    }

    private ProductListing cellWithNoticeGroup(String group) {
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨")
                .categoryNotices(Map.of("소비기한", "2026-12-31"))
                .categoryNoticeGroup(group).build();
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
    }

    @Test
    void register_sharedNoticeKey_usesSelectedGroupNotTheFirstOne() throws Exception {
        ProductListing cell = cellWithNoticeGroup("가공식품");   // NOT the first group in the schema
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(sharedNoticeKeySchema());
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        JsonNode notice = objectMapper.readTree(payload.getValue())
                .path("items").get(0).path("notices").get(0);
        assertThat(notice.path("noticeCategoryDetailName").asText()).isEqualTo("소비기한");
        assertThat(notice.path("noticeCategoryName").asText()).isEqualTo("가공식품");
    }

    @Test
    void register_noticeGroupUnset_keepsFirstWins() throws Exception {
        ProductListing cell = cellWithNoticeGroup(null);   // legacy master, no group stored
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(sharedNoticeKeySchema());
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        assertThat(objectMapper.readTree(payload.getValue())
                .path("items").get(0).path("notices").get(0).path("noticeCategoryName").asText())
                .isEqualTo("농수축산물");
    }

    // ── 96 ⑧: 무료배송(FREE) sends 0 for both charge fields instead of null ──────────────────────────────

    @Test
    void register_freeShipping_sendsZeroForBothChargeFields() throws Exception {
        given(shippingConfigResolver.resolve(any())).willReturn(new ResolvedShippingConfig(
                "OUT-1", "RC-1", "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", null, null,   // deliveryCharge + freeShipOverAmount both null
                "N", "NOT_UNION_DELIVERY", null));
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("deliveryCharge").asInt()).isZero();
        assertThat(json.path("freeShipOverAmount").asInt()).isZero();
    }

    // ── 96 ⑨: required 고시 are validated for every active option, whatever the attribute situation ──────

    /** An active option on a cell whose master picked 가공식품 but left a required notice empty. */
    private ProductListing cellMissingRequiredNotice() {
        MasterProduct master = MasterProduct.builder().id(1L).name("내부 라벨")
                .categoryAttributes(Map.of("원산지", "국내산"))
                .categoryNotices(Map.of("소비기한", "2026-12-31"))   // 포장단위별 용량 left blank
                .categoryNoticeGroup("가공식품").build();
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀").masterProduct(master).build();
    }

    private void givenOneActiveOption() {
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .sellingPrice(new BigDecimal("6000")).active(true).build()));
    }

    @Test
    void validateRegistrable_requiredNoticeBlank_throws() {
        ProductListing cell = cellMissingRequiredNotice();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of(), null)),
                sharedNoticeKeySchema().notices()));
        givenOneActiveOption();

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 고시 누락")
                .hasMessageContaining("포장단위별 용량");
    }

    // 47: a schema with no notices at all (NAVER placeholder) has nothing to enforce.
    @Test
    void validateRegistrable_emptyNoticeSchema_skips() {
        ProductListing cell = cellMissingRequiredNotice();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of(), null)), List.of()));
        givenOneActiveOption();

        assertThatCode(() -> adapter.validateRegistrable(cell, null, acct()))
                .doesNotThrowAnyException();
    }

    // 96 ⑨ regression: the two early returns (AB / empty attribute schema) used to skip the notice check too.
    @Test
    void validateRegistrable_abMaster_stillValidatesNotices() {
        ProductListing cell = cellMissingRequiredNotice();
        given(masterProductService.isBundle(1L)).willReturn(true);   // AB → attributes skipped, notices are not
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of(), null)),
                sharedNoticeKeySchema().notices()));
        givenOneActiveOption();

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 고시 누락");
    }

    @Test
    void validateRegistrable_emptyAttributeSchema_stillValidatesNotices() {
        ProductListing cell = cellMissingRequiredNotice();
        given(masterProductService.isBundle(1L)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(cell)).willReturn("cat-1");
        given(metaAdapter.getMeta(any(), eq("cat-1"))).willReturn(new CategoryMetaSchema(
                List.of(), sharedNoticeKeySchema().notices()));   // notices-only category
        givenOneActiveOption();

        assertThatThrownBy(() -> adapter.validateRegistrable(cell, null, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 고시 누락");
    }

    // ---- 108: displayProductName + requested=true (register) / update identifiers (PUT) ----

    // 108/D2 + D1: the register payload carries the display name and asks for approval; the update-only
    // identifier must NOT leak into it.
    @Test
    void register_sendsDisplayNameAndRequestedTrue() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("10000")).active(true).build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell(), gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("displayProductName").asText()).isEqualTo("셀");
        assertThat(json.path("requested").asBoolean()).isTrue();
        assertThat(json.has("sellerProductId")).isFalse();          // update-only identifier
        assertThat(json.path("items").get(0).has("sellerProductItemId")).isFalse();
    }

    // 108/D3: the update payload identifies the existing product + its already-approved options.
    @Test
    void update_sendsSellerProductIdAndItemIds() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("10000")).active(true)
                        .sellerProductItemId("777").platformOptionId("888").build()));
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.put(anyString(), payload.capture(), any())).willReturn("{\"code\":\"SUCCESS\"}");

        adapter.update(cell(), gen, acct());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("sellerProductId").asText()).isEqualTo("123456789");
        JsonNode item = json.path("items").get(0);
        assertThat(item.path("sellerProductItemId").asText()).isEqualTo("777");
        assertThat(item.path("vendorItemId").asText()).isEqualTo("888");
    }

    // 108/D3: a not-yet-approved option has no market ids → the keys are absent (never null-valued).
    @Test
    void update_omitsItemIdsForNewOption() throws Exception {
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(2L).optionName("2세트")
                        .sellingPrice(new BigDecimal("20000")).active(true).build()));   // ids null
        given(masterProductService.isBundle(null)).willReturn(false);
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.put(anyString(), payload.capture(), any())).willReturn("{\"code\":\"SUCCESS\"}");

        adapter.update(cell(), gen, acct());

        JsonNode item = objectMapper.readTree(payload.getValue()).path("items").get(0);
        assertThat(item.has("sellerProductItemId")).isFalse();
        assertThat(item.has("vendorItemId")).isFalse();
    }

    // ---------------------------------------------------------------- 102: option stock quantity

    /**
     * Registers one option with the given channel/master stock and returns the item's maximumBuyCount.
     * The master option is matched by name ("1세트"), the same axis the adapter's byName map uses.
     */
    private int registeredMaxBuyCount(Integer channelStock, Integer masterStock) throws Exception {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789").masterProduct(master).build();
        given(productListingOptionRepository.findByProductListingId(100L)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("1세트")
                        .sellingPrice(new BigDecimal("10000")).active(true)
                        .stockQuantity(channelStock).build()));
        given(masterProductOptionRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductOption.builder().name("1세트").stockQuantity(masterStock).build()));
        given(masterChannelConfigService.resolvePlatformCategoryCode(any())).willReturn("cat-1");
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any())).willReturn("{\"data\":1}");

        adapter.register(cell, gen, acct());

        return objectMapper.readTree(payload.getValue())
                .path("items").get(0).path("maximumBuyCount").asInt();
    }

    // Neither side carries a stock value → the 9999 fallback. Every pre-102 option row is null, so losing this
    // fallback would push every re-registered product as sold out.
    @Test
    void register_stockUnsetOnBothSides_fallsBackTo9999() throws Exception {
        assertThat(registeredMaxBuyCount(null, null)).isEqualTo(9999);
    }

    @Test
    void register_masterStockOnly_isInherited() throws Exception {
        assertThat(registeredMaxBuyCount(null, 50)).isEqualTo(50);
    }

    @Test
    void register_channelStockOverridesMaster() throws Exception {
        assertThat(registeredMaxBuyCount(10, 50)).isEqualTo(10);
    }

    // 0 is a deliberate "push as sold out" value, never treated as "unset" (D2).
    @Test
    void register_channelStockZero_isSentAsZeroNotInherited() throws Exception {
        assertThat(registeredMaxBuyCount(0, 50)).isZero();
    }
}
