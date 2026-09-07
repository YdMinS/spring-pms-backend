package com.pms.service.listing;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.ListingMasterCreateRequest;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.ListingMasterPreviewResponse;
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
import com.pms.service.MasterProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 판매상품 → 마스터 프로덕트 생성(FEATURE_2609_22 / 04): 미리보기는 <b>저장 0회</b>로 셀↔쿠팡 대조만 하고,
 * 생성은 셀 옵션 그대로 마스터를 만든 뒤 셀·셀 옵션을 붙인다.
 *
 * <p>가장 중요한 회귀 세 가지: ① 옵션마다 물품 집합이 다르거나 한 옵션에 같은 물품이 두 번 있으면
 * <b>마스터를 만들기 전에</b> 400(뒤에서 터지면 수량이 틀린 마스터가 조용히 생긴다) ② 마스터 생성이 셀
 * 저장보다 <b>먼저</b> 일어난다 ③ 셀 옵션은 쿠팡 옵션 id 를 받아오되 가격·이름은 그대로 두고
 * {@code MANUAL_OVERRIDE} 로 잠긴다.</p>
 *
 * <p>⚠️ D31("자산을 만들지 않는다")은 목으로 검증하지 않는다 — 이 서비스는 {@code ListingAssetService} 에
 * <b>의존 자체가 없어</b> 호출이 구조적으로 불가능하다(그쪽이 더 강한 보장이다). 테스트 10 은 그 대신
 * {@code needsMarketSync=false} 를 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class ListingMasterCreateServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private MasterProductService masterProductService;
    @Mock private ListingChannel channel;
    @InjectMocks private ListingMasterCreateServiceImpl service;

    private static final Long LISTING_ID = 300L;
    private static final Long SELLER_ID = 7L;
    private static final Long MASTER_ID = 88L;
    private static final Long CATEGORY_ID = 41L;
    private static final Long PRODUCT_A = 100L;
    private static final Long PRODUCT_B = 200L;
    private static final Platform PLATFORM = Platform.COUPANG;
    private static final String PRODUCT_ID = "222333444";
    private static final String COUPANG_CATEGORY = "63955";

    // ---- fixtures ----

    private Product product(Long id) {
        return Product.builder().id(id).brand("노브랜드").productName("생수 " + id).build();
    }

    private ProductListing cell() {
        return ProductListing.builder()
                .id(LISTING_ID).platform(PLATFORM).platformProductId(PRODUCT_ID)
                .name("노브랜드 생수 2L").status(ListingStatus.SELLING)
                .seller(Seller.builder().id(SELLER_ID).sellerName("행복상회").build())
                .delivery(CarrierRate.builder().id(4L).build())
                .package_(Package.builder().id(5L).build())
                .build();
    }

    private ProductListingOption cellOption(Long id, String name, String platformOptionId, String price) {
        return ProductListingOption.builder()
                .id(id).productListing(cell()).optionName(name)
                .platformOptionId(platformOptionId)
                .sellingPrice(new BigDecimal(price)).originalPrice(new BigDecimal(price))
                .stockQuantity(50).active(true)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .build();
    }

    private ProductListingProduct bomLine(Long optionId, Long productId, Integer quantity) {
        return ProductListingProduct.builder()
                .productListingOption(ProductListingOption.builder().id(optionId).build())
                .product(product(productId))
                .quantity(quantity)
                .build();
    }

    private ImportedProduct.Option marketOption(String itemName, String vendorItemId, String salePrice) {
        return new ImportedProduct.Option(itemName, vendorItemId, "9" + vendorItemId,
                new BigDecimal(salePrice), new BigDecimal(salePrice), 50);
    }

    private ImportedProduct marketProduct(ImportedProduct.Option... options) {
        return new ImportedProduct("노브랜드 생수 2L 6입/12입", COUPANG_CATEGORY, ListingStatus.SELLING,
                List.of("생수"), List.of(options));
    }

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder().id(9L).platform(PLATFORM).isActive(true)
                .seller(Seller.builder().id(SELLER_ID).build()).build();
    }

    /** 옵션 2건 · 공통 물품 2종 · 쿠팡도 같은 옵션 2건인 정상 셀. */
    private void givenHealthyCell(String option1OptionId, String option1Price, String option1MarketPrice) {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                cellOption(1L, "6입", option1OptionId, option1Price),
                cellOption(2L, "12입", "8123456790", "10900")));
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                bomLine(1L, PRODUCT_A, 6), bomLine(1L, PRODUCT_B, 1),
                bomLine(2L, PRODUCT_A, 12), bomLine(2L, PRODUCT_B, 1)));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account()));
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(marketProduct(
                marketOption("6입", "8123456789", option1MarketPrice),
                marketOption("12입", "8123456790", "10900")));
    }

    private void givenHealthyCell() {
        givenHealthyCell("8123456789", "5900", "5900");
    }

    private void givenCreatePath() {
        given(masterProductService.createMasterProduct(any()))
                .willReturn(MasterProductResponse.builder().id(MASTER_ID).build());
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).build()));
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(
                MasterProductOption.builder().id(11L).name("6입").build(),
                MasterProductOption.builder().id(12L).name("12입").build()));
        given(productListingRepository.save(any())).willAnswer(i -> i.getArgument(0));
    }

    private ListingMasterCreateRequest createRequest() {
        return ListingMasterCreateRequest.builder()
                .masterName("노브랜드 생수 2L").categoryId(CATEGORY_ID).build();
    }

    // ---- preview ----

    @Test
    void testPreviewReturnsDiffAndSuggestions() {
        // 6입 = 셀 옵션 id 가 틀렸고(교정 대상), 12입 = 가격이 다르다.
        givenHealthyCell("8123456780", "5900", "5900");
        given(productRepository.findAllById(any())).willReturn(List.of(product(PRODUCT_A), product(PRODUCT_B)));
        given(productRepository.findById(PRODUCT_A)).willReturn(Optional.of(product(PRODUCT_A)));

        ListingMasterPreviewResponse response = service.preview(LISTING_ID);

        assertThat(response.getListingName()).isEqualTo("노브랜드 생수 2L");
        assertThat(response.getCoupangProductName()).isEqualTo("노브랜드 생수 2L 6입/12입");
        assertThat(response.getStatus()).isEqualTo(ListingStatus.SELLING);
        // D25: 옵션명 오름차순 첫 옵션("12입") 의 BOM 중 productId 오름차순 첫 물품 = PRODUCT_A.
        assertThat(response.getSuggestedMasterName()).isEqualTo("노브랜드 생수 100");
        assertThat(response.getOptions()).hasSize(2);
        assertThat(response.getOptions().get(0).isOptionIdMismatch()).isTrue();
        assertThat(response.getOptions().get(0).getCoupangVendorItemId()).isEqualTo("8123456789");
        assertThat(response.getOptions().get(0).isPriceMismatch()).isFalse();
        assertThat(response.getOptions().get(1).isOptionIdMismatch()).isFalse();
        assertThat(response.getComponents()).hasSize(2);
        assertThat(response.getCoupangOnlyOptions()).isEmpty();
        // 미리보기는 저장하지 않는다.
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).save(any());
        verify(masterProductService, never()).createMasterProduct(any());
    }

    @Test
    void testPreviewReportsPriceMismatchAndCoupangOnlyOptions() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(cellOption(1L, "6입", "8123456789", "10900")));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(bomLine(1L, PRODUCT_A, 6)));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account()));
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(marketProduct(
                marketOption("6입", "8123456789", "11900"),
                marketOption("24입", "8123456799", "20900")));
        given(productRepository.findAllById(any())).willReturn(List.of(product(PRODUCT_A)));
        given(productRepository.findById(PRODUCT_A)).willReturn(Optional.of(product(PRODUCT_A)));

        ListingMasterPreviewResponse response = service.preview(LISTING_ID);

        assertThat(response.getOptions().get(0).isPriceMismatch()).isTrue();
        assertThat(response.getOptions().get(0).getCurrentPrice()).isEqualByComparingTo("10900");
        // D30: 쿠팡에만 있는 옵션은 가져오지 않고 경고만 한다.
        assertThat(response.getCoupangOnlyOptions()).containsExactly("24입");
    }

    @Test
    void testPreviewSuggestsCategoryFromLeafCode() {
        givenHealthyCell();
        given(productRepository.findAllById(any())).willReturn(List.of(product(PRODUCT_A), product(PRODUCT_B)));
        given(productRepository.findById(PRODUCT_A)).willReturn(Optional.of(product(PRODUCT_A)));
        PlatformCategory platformCategory = PlatformCategory.builder().id(70L).build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(70L)).willReturn(Optional.of(
                CategoryMapping.builder()
                        .category(Category.builder().id(CATEGORY_ID).name("생수").build()).build()));

        ListingMasterPreviewResponse response = service.preview(LISTING_ID);

        assertThat(response.getCategoryCode()).isEqualTo(COUPANG_CATEGORY);
        assertThat(response.getSuggestedCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.getSuggestedCategoryName()).isEqualTo("생수");
    }

    @Test
    void testPreviewLeavesCategoryNullWhenReverseLookupFails() {
        givenHealthyCell();
        given(productRepository.findAllById(any())).willReturn(List.of(product(PRODUCT_A), product(PRODUCT_B)));
        given(productRepository.findById(PRODUCT_A)).willReturn(Optional.of(product(PRODUCT_A)));
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.empty());

        ListingMasterPreviewResponse response = service.preview(LISTING_ID);

        // D26: 실패는 null — 프론트가 사용자에게 표준 카테고리를 고르게 한다(경고 문구를 쓰지 않는다).
        assertThat(response.getSuggestedCategoryId()).isNull();
        assertThat(response.getSuggestedCategoryName()).isNull();
    }

    @Test
    void testPreviewRejectsAlreadyLinked() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(
                cell().toBuilder().masterProduct(MasterProduct.builder().id(MASTER_ID).build()).build()));

        assertThatThrownBy(() -> service.preview(LISTING_ID))
                .hasMessageContaining("이미 마스터에 연결된 판매상품");
    }

    @Test
    void testPreviewRejectsDifferentComponentSets() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                cellOption(1L, "6입", "8123456789", "5900"),
                cellOption(2L, "12입", "8123456790", "10900")));
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                bomLine(1L, PRODUCT_A, 6),
                bomLine(2L, PRODUCT_B, 12)));

        assertThatThrownBy(() -> service.preview(LISTING_ID))
                .hasMessageContaining("옵션마다 구성 물품이 달라");
        verify(channel, never()).fetchProduct(anyString(), any());
    }

    @Test
    void testPreviewRejectsDuplicateBomProductInOption() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(cellOption(1L, "6입", "8123456789", "5900")));
        // 🔴 같은 물품 두 줄 — toVector 가 마지막 값으로 덮어써 수량이 틀린 마스터를 조용히 만든다.
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                bomLine(1L, PRODUCT_A, 6),
                bomLine(1L, PRODUCT_A, 2)));

        assertThatThrownBy(() -> service.preview(LISTING_ID))
                .hasMessageContaining("같은 물품이 두 번 들어간 옵션");
        verify(productListingRepository, never()).save(any());
        verify(masterProductService, never()).createMasterProduct(any());
    }

    @Test
    void testPreviewRejectsCellOptionMissingInCoupang() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                cellOption(1L, "6입", "8123456789", "5900"),
                cellOption(2L, "12입", null, "10900")));
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                bomLine(1L, PRODUCT_A, 6), bomLine(2L, PRODUCT_A, 12)));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account()));
        given(channel.fetchProduct(eq(PRODUCT_ID), any()))
                .willReturn(marketProduct(marketOption("6입", "8123456789", "5900")));

        assertThatThrownBy(() -> service.preview(LISTING_ID))
                .hasMessageContaining("쿠팡에 없는 옵션이 있습니다: 12입");
    }

    @Test
    void testPreviewRejectsCoupangNotFound() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell()));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(cellOption(1L, "6입", "8123456789", "5900")));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(bomLine(1L, PRODUCT_A, 6)));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account()));
        given(channel.fetchProduct(eq(PRODUCT_ID), any()))
                .willThrow(new IllegalStateException("404 Not Found"));

        assertThatThrownBy(() -> service.preview(LISTING_ID))
                .hasMessageContaining("쿠팡에서 상품을 찾을 수 없습니다");
    }

    // ---- create ----

    @Test
    void testCreateBuildsMasterFromCellOptions() {
        givenHealthyCell();
        givenCreatePath();

        ListingMasterCreateResponse response = service.create(LISTING_ID, createRequest());

        ArgumentCaptor<MasterProductRequest> captor = ArgumentCaptor.forClass(MasterProductRequest.class);
        verify(masterProductService).createMasterProduct(captor.capture());
        MasterProductRequest request = captor.getValue();
        assertThat(request.getName()).isEqualTo("노브랜드 생수 2L");
        assertThat(request.getComponentProductIds()).containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B);
        assertThat(request.getDefaultDeliveryId()).isEqualTo(4L);
        assertThat(request.getDefaultPackageId()).isEqualTo(5L);
        assertThat(request.getOptions()).hasSize(2);
        MasterOptionRequest first = request.getOptions().get(0);
        assertThat(first.getName()).isEqualTo("6입");
        assertThat(first.getStockQuantity()).isEqualTo(50);
        assertThat(first.getItems()).extracting(MasterOptionRequest.OptionItem::getProductId)
                .containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B);
        assertThat(first.getItems()).extracting(MasterOptionRequest.OptionItem::getQuantity)
                .containsExactlyInAnyOrder(6, 1);
        // D26: 카테고리는 마스터 경로의 기존 가드를 그대로 탄다.
        ArgumentCaptor<MasterCategoryRequest> category = ArgumentCaptor.forClass(MasterCategoryRequest.class);
        verify(masterProductService).setCategory(eq(MASTER_ID), category.capture());
        assertThat(category.getValue().getCategoryId()).isEqualTo(CATEGORY_ID);

        assertThat(response.getMasterProductId()).isEqualTo(MASTER_ID);
        assertThat(response.getProductListingId()).isEqualTo(LISTING_ID);
        assertThat(response.getOptionCount()).isEqualTo(2);
    }

    @Test
    void testCreateLinksCellAndOptionsAfterMasterCreated() {
        givenHealthyCell();
        givenCreatePath();

        service.create(LISTING_ID, createRequest());

        // 🔴 마스터가 먼저 만들어져야 한다 — 셀을 먼저 붙이면 옵션 전파가 이 셀까지 훑는다.
        InOrder order = inOrder(masterProductService, productListingRepository, productListingOptionRepository);
        order.verify(masterProductService).createMasterProduct(any());
        order.verify(productListingRepository).save(any());
        order.verify(productListingOptionRepository, org.mockito.Mockito.times(2)).save(any());

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getMasterProduct().getId()).isEqualTo(MASTER_ID);
        assertThat(cellCaptor.getValue().getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(cellCaptor.getValue().getPlatformCategoryCode()).isEqualTo(COUPANG_CATEGORY);

        ArgumentCaptor<ProductListingOption> optionCaptor =
                ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, org.mockito.Mockito.times(2)).save(optionCaptor.capture());
        assertThat(optionCaptor.getAllValues()).extracting(o -> o.getMasterProductOption().getId())
                .containsExactly(11L, 12L);
    }

    @Test
    void testCreateFixesOptionIdAndKeepsPrice() {
        // 셀 옵션 id 가 틀렸고 셀·쿠팡 가격이 다른 상황 — 반대 방향 두 규칙을 한 번에 본다.
        givenHealthyCell("8123456780", "5900", "6900");
        givenCreatePath();

        service.create(LISTING_ID, createRequest());

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        ProductListingOption saved = captor.getAllValues().get(0);
        // D27 자동 교정 + D20
        assertThat(saved.getPlatformOptionId()).isEqualTo("8123456789");
        assertThat(saved.getSellerProductItemId()).isEqualTo("98123456789");
        assertThat(saved.getApprovalStatus()).isEqualTo(OptionApprovalStatus.APPROVED);
        // 가격·이름은 무변경 — 이 기능은 가격을 동기화하지 않는다.
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("5900");
        assertThat(saved.getOriginalPrice()).isEqualByComparingTo("5900");
        assertThat(saved.getOptionName()).isEqualTo("6입");
        // D12·D13: 재생성이 계산가·마스터 옵션명으로 덮지 못하게 잠근다.
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(saved.getOptionNameSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
    }

    @Test
    void testCreateDoesNotGenerateAssets() {
        givenHealthyCell();
        givenCreatePath();

        service.create(LISTING_ID, createRequest());

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        // D31: 얕은 생성 — 자산이 없으므로 layer B(pendingSync) 대상이 되지 않는다.
        assertThat(captor.getValue().isNeedsMarketSync()).isFalse();
    }

    @Test
    void testCreateRevalidatesWhenPreviewSkipped() {
        // 미리보기를 건너뛴 직접 호출도 같은 검증을 탄다.
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(
                cell().toBuilder().masterProduct(MasterProduct.builder().id(1L).build()).build()));

        assertThatThrownBy(() -> service.create(LISTING_ID, createRequest()))
                .hasMessageContaining("이미 마스터에 연결된 판매상품");
        verify(masterProductService, never()).createMasterProduct(any());
        verify(masterProductService, never()).setCategory(anyLong(), any());
    }
}
