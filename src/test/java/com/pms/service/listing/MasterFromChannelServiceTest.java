package com.pms.service.listing;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.request.MasterFromChannelRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.MasterFromChannelPreviewResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.CategoryMetaService;
import com.pms.service.ListingAssetService;
import com.pms.service.MasterProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 마켓 상품으로 마스터 만들기(FEATURE_2609_45 / 01). 미리보기는 <b>저장 0회</b>로 옵션·카테고리·속성만 만들고,
 * 커밋은 마켓을 다시 읽어 마스터·옵션·셀을 한 번에 만든다.
 *
 * <p>가장 중요한 회귀 셋: ① 옵션마다 다른 속성은 <b>그 마스터 옵션</b>에 실린다(D4-1) ② 마스터 옵션에 재고를
 * 넣지 않는다(D3-1, 상한 오염) ③ 이 서비스에는 {@code ListingAssetService} 의존성 자체가 없다(D5).</p>
 */
@ExtendWith(MockitoExtension.class)
class MasterFromChannelServiceTest {

    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private MasterProductService masterProductService;
    @Mock private CategoryMetaService categoryMetaService;
    @Mock private ListingChannel channel;
    @InjectMocks private MasterFromChannelServiceImpl service;

    private static final Long SELLER_ID = 7L;
    private static final Long MASTER_ID = 55L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long PRODUCT_A = 100L;
    private static final Long PRODUCT_B = 200L;
    private static final Platform PLATFORM = Platform.COUPANG;
    private static final String PRODUCT_ID = "222333444";
    private static final String COUPANG_CATEGORY = "73170";

    // ---- fixtures ----

    private Product product(Long id) {
        return Product.builder().id(id).brand("노브랜드").productName("상품" + id).build();
    }

    private ImportedProduct.Option marketOption(String name, String vendorItemId, String salePrice,
                                                Map<String, String> attributes) {
        return new ImportedProduct.Option(name, vendorItemId, "9" + vendorItemId,
                new BigDecimal(salePrice), new BigDecimal(salePrice).add(BigDecimal.valueOf(3000)), 85,
                attributes, Map.of("제품명", "상품 상세페이지 참조"));
    }

    private ImportedProduct marketProduct(ImportedProduct.Option... options) {
        return new ImportedProduct("노브랜드 생수 2L 6입", COUPANG_CATEGORY, ListingStatus.SELLING,
                List.of("생수", "2L"), "가공식품", List.of(options));
    }

    /** 옵션 2개: 공통 속성(개당 중량) + 옵션마다 다른 속성(수량). */
    private ImportedProduct twoOptionProduct() {
        return marketProduct(
                marketOption("6입", "8123", "12900", Map.of("수량", "6", "개당 중량", "36.9")),
                marketOption("12입", "8124", "23900", Map.of("수량", "12", "개당 중량", "36.9")));
    }

    private MasterFromChannelPreviewRequest previewRequest() {
        return MasterFromChannelPreviewRequest.builder()
                .sellerId(SELLER_ID).platform(PLATFORM.name()).platformProductId(PRODUCT_ID).build();
    }

    private MasterFromChannelRequest.OptionSpec spec(String itemName, String platformOptionId,
                                                     int qtyA, Integer qtyB) {
        List<MasterFromChannelRequest.Component> components = qtyB == null
                ? List.of(MasterFromChannelRequest.Component.builder()
                        .productId(PRODUCT_A).quantity(qtyA).build())
                : List.of(
                        MasterFromChannelRequest.Component.builder().productId(PRODUCT_A).quantity(qtyA).build(),
                        MasterFromChannelRequest.Component.builder().productId(PRODUCT_B).quantity(qtyB).build());
        return MasterFromChannelRequest.OptionSpec.builder()
                .itemName(itemName).platformOptionId(platformOptionId).components(components).build();
    }

    private MasterFromChannelRequest createRequest(List<Long> componentIds,
                                                   MasterFromChannelRequest.OptionSpec... specs) {
        return MasterFromChannelRequest.builder()
                .sellerId(SELLER_ID).platform(PLATFORM.name()).platformProductId(PRODUCT_ID)
                .masterName("노브랜드 생수 2L").categoryId(CATEGORY_ID)
                .componentProductIds(componentIds).options(List.of(specs)).build();
    }

    // ---- stub helpers (kept granular: a guard test must not stub what it never reaches) ----

    private void givenAccount() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(PLATFORM).isActive(true).build()));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
    }

    private void givenMarket(ImportedProduct product) {
        given(productListingRepository.existsByPlatformProductId(PRODUCT_ID)).willReturn(false);
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(product);
    }

    /** D2 역조회 성공. */
    private void givenCategoryResolved() {
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수").build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(CATEGORY_ID).name("생수").build())
                        .platformCategory(platformCategory).build()));
    }

    /** 마스터 생성 + 셀 저장이 id 를 돌려주도록. */
    private void givenCreationSucceeds(String... masterOptionNames) {
        given(masterProductService.createMasterProduct(any()))
                .willReturn(MasterProductResponse.builder().id(MASTER_ID).build());
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).name("노브랜드 생수 2L").build()));
        List<MasterProductOption> options = new java.util.ArrayList<>();
        long id = 300L;
        for (String name : masterOptionNames) {
            options.add(MasterProductOption.builder().id(id++).name(name).build());
        }
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(options);
        given(productListingRepository.save(any())).willAnswer(inv ->
                ((ProductListing) inv.getArgument(0)).toBuilder().id(50L).build());
        given(productListingOptionRepository.save(any())).willAnswer(inv ->
                ((ProductListingOption) inv.getArgument(0)).toBuilder().id(60L).build());
    }

    private void givenComponents(Long... productIds) {
        given(productRepository.findAllById(any()))
                .willReturn(java.util.Arrays.stream(productIds).map(this::product).toList());
    }

    private void verifyNothingSaved() {
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).save(any());
        verify(productListingProductRepository, never()).save(any());
        verify(masterProductService, never()).createMasterProduct(any());
    }

    // ---- preview ----

    @Test
    void preview_returnsOptionsAndCategory() {
        givenAccount();
        givenMarket(twoOptionProduct());
        givenCategoryResolved();

        MasterFromChannelPreviewResponse response = service.preview(previewRequest());

        assertThat(response.getProductName()).isEqualTo("노브랜드 생수 2L 6입");
        assertThat(response.getSuggestedMasterName()).isEqualTo("노브랜드 생수 2L 6입");
        assertThat(response.getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(response.isCategoryResolved()).isTrue();
        assertThat(response.getSuggestedCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.getSuggestedCategoryName()).isEqualTo("생수");
        assertThat(response.getOptions()).extracting(MasterFromChannelPreviewResponse.Option::getItemName)
                .containsExactly("6입", "12입");
        assertThat(response.getOptions().get(0).getPlatformOptionId()).isEqualTo("8123");
        assertThat(response.getOptions().get(0).getStockQuantity()).isEqualTo(85);
        // D4-1: 공통은 마스터 몫, 상이는 그 옵션 몫.
        assertThat(response.getCommonAttributes()).containsExactly(Map.entry("개당 중량", "36.9"));
        assertThat(response.getOptions().get(0).getAttributes()).containsExactly(Map.entry("수량", "6"));
        assertThat(response.getOptions().get(1).getAttributes()).containsExactly(Map.entry("수량", "12"));
        assertThat(response.getNotices()).containsEntry("제품명", "상품 상세페이지 참조");
        assertThat(response.getNoticeGroup()).isEqualTo("가공식품");
        // 🔴 미리보기는 아무것도 쓰지 않는다.
        verifyNothingSaved();
    }

    @Test
    void preview_categoryNotMapped_returnsNullCategory() {
        givenAccount();
        givenMarket(twoOptionProduct());
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.empty());

        MasterFromChannelPreviewResponse response = service.preview(previewRequest());

        // 예외가 아니다 — 프론트가 사용자에게 표준 카테고리를 고르게 한다(D2).
        assertThat(response.isCategoryResolved()).isFalse();
        assertThat(response.getSuggestedCategoryId()).isNull();
        assertThat(response.getSuggestedCategoryName()).isNull();
        assertThat(response.getCategoryCode()).isEqualTo(COUPANG_CATEGORY);
    }

    @Test
    void preview_duplicateItemName_throws400() {
        givenAccount();
        givenMarket(marketProduct(
                marketOption("6입", "8123", "12900", Map.of()),
                marketOption("6입", "8124", "23900", Map.of())));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("옵션명이 중복됩니다")
                .hasMessageContaining("6입");
    }

    @Test
    void preview_zeroPrice_throws400() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "0", Map.of())));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("판매가 없는 옵션: 6입");
    }

    @Test
    void preview_alreadyLinkedProductId_throws400() {
        givenAccount();
        given(productListingRepository.existsByPlatformProductId(PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 다른 상품에 연결된 쿠팡 상품입니다");
        verify(channel, never()).fetchProduct(any(), any());
    }

    // ---- create ----

    @Test
    void create_buildsMasterWithMarketOptionNames() {
        givenAccount();
        givenMarket(twoOptionProduct());
        givenComponents(PRODUCT_A, PRODUCT_B);
        givenCreationSucceeds("6입", "12입");

        ListingMasterCreateResponse response = service.create(createRequest(
                List.of(PRODUCT_A, PRODUCT_B), spec("6입", "8123", 6, 1), spec("12입", "8124", 12, 2)));

        ArgumentCaptor<MasterProductRequest> captor = ArgumentCaptor.forClass(MasterProductRequest.class);
        verify(masterProductService).createMasterProduct(captor.capture());
        MasterProductRequest created = captor.getValue();
        assertThat(created.getName()).isEqualTo("노브랜드 생수 2L");
        assertThat(created.getComponentProductIds()).containsExactly(PRODUCT_A, PRODUCT_B);
        assertThat(created.getOptions()).extracting(MasterOptionRequest::getName)
                .containsExactly("6입", "12입");     // 마스터 옵션명 = 쿠팡 itemName
        assertThat(created.getOptions().get(0).getItems())
                .extracting(MasterOptionRequest.OptionItem::getProductId,
                        MasterOptionRequest.OptionItem::getQuantity)
                .containsExactly(org.assertj.core.api.Assertions.tuple(PRODUCT_A, 6),
                        org.assertj.core.api.Assertions.tuple(PRODUCT_B, 1));
        // 🔴 D3-1: 마스터 옵션 재고는 비운다 — 채널 재고의 상한이라 한 채널의 값(85)에 다른 채널이 갇힌다.
        assertThat(created.getOptions()).allSatisfy(o -> assertThat(o.getStockQuantity()).isNull());
        // 택배·박스는 쿠팡에 개념이 없다(D5 얕은 생성과 같은 성격).
        assertThat(created.getDefaultDeliveryId()).isNull();
        assertThat(created.getDefaultPackageId()).isNull();

        verify(masterProductService).setCategory(eq(MASTER_ID), any(MasterCategoryRequest.class));
        assertThat(response.getMasterProductId()).isEqualTo(MASTER_ID);
        assertThat(response.getProductListingId()).isEqualTo(50L);
        assertThat(response.getOptionCount()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(ListingStatus.SELLING);
    }

    @Test
    void create_storesCommonAttributesOnMaster() {
        givenAccount();
        givenMarket(twoOptionProduct());
        givenComponents(PRODUCT_A);
        givenCreationSucceeds("6입", "12입");

        service.create(createRequest(List.of(PRODUCT_A), spec("6입", "8123", 6, null),
                spec("12입", "8124", 12, null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> notices = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> group = ArgumentCaptor.forClass(String.class);
        verify(categoryMetaService).updateCategoryAttributes(eq(MASTER_ID), attributes.capture(),
                notices.capture(), group.capture());
        // 전 옵션이 같은 값을 갖는 키만 마스터로 간다.
        assertThat(attributes.getValue()).containsExactly(Map.entry("개당 중량", "36.9"));
        assertThat(notices.getValue()).containsEntry("제품명", "상품 상세페이지 참조");
        assertThat(group.getValue()).isEqualTo("가공식품");
    }

    /** 🔴 D4-1 의 회귀 가드: 옵션마다 다른 속성이 그 마스터 옵션에 실려야 한다. */
    @Test
    void create_storesDifferingAttributesOnEachOption() {
        givenAccount();
        givenMarket(twoOptionProduct());
        givenComponents(PRODUCT_A);
        givenCreationSucceeds("6입", "12입");

        service.create(createRequest(List.of(PRODUCT_A), spec("6입", "8123", 6, null),
                spec("12입", "8124", 12, null)));

        ArgumentCaptor<MasterProductRequest> captor = ArgumentCaptor.forClass(MasterProductRequest.class);
        verify(masterProductService).createMasterProduct(captor.capture());
        List<MasterOptionRequest> options = captor.getValue().getOptions();
        assertThat(options.get(0).getCategoryAttributes()).containsExactly(Map.entry("수량", "6"));
        assertThat(options.get(1).getCategoryAttributes()).containsExactly(Map.entry("수량", "12"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> masterAttributes = ArgumentCaptor.forClass(Map.class);
        verify(categoryMetaService).updateCategoryAttributes(eq(MASTER_ID), masterAttributes.capture(),
                any(), any());
        // 마스터 공통 맵에는 "수량" 이 없다 — items[0] 값을 전 옵션에 공통 적용하면 6개입 수량이 12개입으로 나간다.
        assertThat(masterAttributes.getValue()).doesNotContainKey("수량");
    }

    /** 🔴 D5: 이 서비스에 ListingAssetService 필드가 없음을 테스트로 고정한다(주입하면 여기서 드러난다). */
    @Test
    void create_neverCallsRegenerateAssets() {
        assertThat(MasterFromChannelServiceImpl.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(ListingAssetService.class);
    }

    @Test
    void create_optionMismatch_throws400() {
        givenAccount();
        givenMarket(twoOptionProduct());

        assertThatThrownBy(() -> service.create(createRequest(List.of(PRODUCT_A),
                spec("6입", "8123", 6, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("쿠팡 옵션이 변경되었습니다");
        verify(masterProductService, never()).createMasterProduct(any());
    }

    @Test
    void create_missingComponentQuantity_throws400() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900", Map.of())));
        givenComponents(PRODUCT_A, PRODUCT_B);

        // 구성상품은 A·B 인데 옵션 수량은 A 만 채웠다(D6).
        assertThatThrownBy(() -> service.create(createRequest(List.of(PRODUCT_A, PRODUCT_B),
                spec("6입", "8123", 6, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6입")
                .hasMessageContaining("상품" + PRODUCT_B);
        verify(masterProductService, never()).createMasterProduct(any());
    }

    @Test
    void create_linksCellOptionToMasterOption() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900", Map.of())));
        givenComponents(PRODUCT_A);
        givenCreationSucceeds("6입");

        service.create(createRequest(List.of(PRODUCT_A), spec("6입", "8123", 6, null)));

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        ProductListingOption saved = captor.getValue();
        assertThat(saved.getMasterProductOption()).isNotNull();
        assertThat(saved.getMasterProductOption().getName()).isEqualTo("6입");
        assertThat(saved.getOptionName()).isEqualTo("6입");
        assertThat(saved.getOptionNameSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("12900");
        assertThat(saved.getStockQuantity()).isEqualTo(85);     // 쿠팡 재고는 셀 옵션에만 들어간다
        assertThat(saved.getPlatformOptionId()).isEqualTo("8123");
        // 셀 레벨 속성·고시는 넣지 않는다 — 마스터 카테고리 = 쿠팡 카테고리라 마스터 값이 곧 정답이다.
        assertThat(saved.getCategoryAttributes()).isNull();
        assertThat(saved.getCategoryNotices()).isNull();
    }

    @Test
    void create_cellCarriesMarketTagsAndStatus() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900", Map.of())));
        givenComponents(PRODUCT_A);
        givenCreationSucceeds("6입");

        service.create(createRequest(List.of(PRODUCT_A), spec("6입", "8123", 6, null)));

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        ProductListing cell = captor.getValue();
        assertThat(cell.getPlatformProductId()).isEqualTo(PRODUCT_ID);
        assertThat(cell.getPlatformCategoryCode()).isEqualTo(COUPANG_CATEGORY);
        assertThat(cell.getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(cell.isNeedsMarketSync()).isFalse();
        // 신규 마스터는 태그가 비어 차집합이 항상 원본과 같다 — 쿠팡 태그를 그대로 넣는다.
        assertThat(cell.getTags()).containsExactly("생수", "2L");
    }

    // D. 🔴 2609_45/D2-1: 역조회가 실패(또는 다른 카테고리로 해석)해 사용자가 **다른** 표준 카테고리를 고른
    //    경우, 그 셀은 쿠팡 코드를 갖고 있으므로 02 의 해석에서 자기 카테고리로 살아난다 → 셀 옵션 메타를
    //    채워야 한다(안 채우면 [수정 요청]이 필수 속성 없이 나간다).
    @Test
    void create_userPickedDifferentCategory_fillsCellOptionMeta() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900", Map.of("수량", "6"))));
        givenComponents(PRODUCT_A);
        givenCreationSucceeds("6입");
        // 쿠팡 카테고리는 다른 표준 카테고리(999)로 해석되고, 그 카테고리에는 수수료가 있다(D11 통과).
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수")
                .commissionRate(new BigDecimal("0.11")).build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(999L).name("다른 카테고리").build())
                        .platformCategory(platformCategory).build()));

        service.create(createRequest(List.of(PRODUCT_A), spec("6입", "8123", 6, null)));

        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getCategoryAttributes()).containsEntry("수량", "6");
        assertThat(optionCaptor.getValue().getCategoryNotices()).containsEntry("제품명", "상품 상세페이지 참조");

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getCategoryNoticeGroup()).isEqualTo("가공식품");
    }
}
