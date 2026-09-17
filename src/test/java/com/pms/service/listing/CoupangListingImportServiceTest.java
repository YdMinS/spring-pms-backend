package com.pms.service.listing;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.dto.response.ListingImportPreviewResponse;
import com.pms.exception.DuplicateChannelException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ListingAssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 마켓 상품 가져오기(FEATURE_2609_22 / 02): 미리보기는 <b>저장 0회</b>로 매핑 판정과 입력 줄만 만들고, 커밋은
 * 마켓을 다시 읽어 구성이 같은 마스터 옵션에 붙이거나 새 옵션을 만든 뒤 셀·셀 옵션·셀 BOM 을 만든다.
 *
 * <p>가장 중요한 회귀 두 가지: ① 정방향 카테고리 매핑 부재는 <b>경고가 아니라 400</b>(역조회 불일치와 다르다)
 * ② 셀 옵션은 쿠팡의 이름·가격을 {@code MANUAL_OVERRIDE} 로 들고 있어야 재생성이 덮어쓰지 않는다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CoupangListingImportServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductComponentRepository masterProductComponentRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private MasterOptionChannelSync masterOptionChannelSync;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingChannel channel;
    @InjectMocks private CoupangListingImportServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void wireSelf() {
        // @InjectMocks skips a field whose type is the class under test → `self` would stay null and
        // importListing() would die with an NPE. A unit test has no proxy, so inject the instance itself
        // (importListing → importInTransaction runs straight through; the transaction boundary is the
        // integration test's job).
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static final Long MASTER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long PRODUCT_A = 100L;
    private static final Long PRODUCT_B = 200L;
    private static final Platform PLATFORM = Platform.COUPANG;
    private static final String PRODUCT_ID = "1234567";
    private static final String COUPANG_CATEGORY = "72882";

    // ---- fixtures ----

    private Product product(Long id) {
        return Product.builder().id(id).brand("노브랜드").productName("상품" + id).build();
    }

    /** Master with a standard category and two shared tags (one of which the market also carries). */
    private MasterProduct master() {
        return MasterProduct.builder().id(MASTER_ID).name("마스터").active(true)
                .category(Category.builder().id(CATEGORY_ID).name("생수").build())
                .tags(List.of("생수"))
                .build();
    }

    private List<MasterProductComponent> components() {
        return List.of(
                MasterProductComponent.builder().id(1L).product(product(PRODUCT_A)).build(),
                MasterProductComponent.builder().id(2L).product(product(PRODUCT_B)).build());
    }

    private ImportedProduct.Option marketOption(String name, String vendorItemId, String salePrice) {
        return new ImportedProduct.Option(name, vendorItemId, "9" + vendorItemId,
                new BigDecimal(salePrice), new BigDecimal(salePrice).add(BigDecimal.valueOf(3000)), 50);
    }

    private ImportedProduct marketProduct(ImportedProduct.Option... options) {
        return new ImportedProduct("노브랜드 생수 2L 6입", COUPANG_CATEGORY, ListingStatus.SELLING,
                List.of("생수", "2L"), List.of(options));
    }

    private ListingImportPreviewRequest previewRequest() {
        return ListingImportPreviewRequest.builder()
                .sellerId(SELLER_ID).platform(PLATFORM.name()).platformProductId(PRODUCT_ID).build();
    }

    private ListingImportRequest.OptionSpec spec(String itemName, String vendorItemId, int qtyA, Integer qtyB) {
        List<ListingImportRequest.Component> components = qtyB == null
                ? List.of(ListingImportRequest.Component.builder().productId(PRODUCT_A).quantity(qtyA).build())
                : List.of(
                        ListingImportRequest.Component.builder().productId(PRODUCT_A).quantity(qtyA).build(),
                        ListingImportRequest.Component.builder().productId(PRODUCT_B).quantity(qtyB).build());
        return ListingImportRequest.OptionSpec.builder()
                .itemName(itemName).vendorItemId(vendorItemId).masterOptionName(itemName)
                .components(components).build();
    }

    private ListingImportRequest importRequest(ListingImportRequest.OptionSpec... specs) {
        return ListingImportRequest.builder()
                .sellerId(SELLER_ID).platform(PLATFORM.name()).platformProductId(PRODUCT_ID)
                .options(List.of(specs)).build();
    }

    private MasterProductOption masterOption(Long id, String name) {
        return MasterProductOption.builder().id(id).name(name).masterProduct(master()).build();
    }

    // ---- stub helpers (kept granular: a guard test must not stub what it never reaches) ----

    private void givenMaster() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(masterProductComponentRepository.findByMasterProductId(MASTER_ID)).willReturn(components());
    }

    private void givenForwardMapping(boolean present) {
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(CATEGORY_ID, PLATFORM)).willReturn(present);
    }

    private void givenAccount() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(PLATFORM).isActive(true).build()));
    }

    private void givenNoDuplicateChannel() {
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, PLATFORM))
                .willReturn(false);
    }

    private void givenMarket(ImportedProduct product) {
        given(productListingRepository.existsByPlatformProductId(PRODUCT_ID)).willReturn(false);
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(product);
    }

    /** Reverse lookup (D14) resolves to a DIFFERENT standard category → mismatch. */
    private void givenCategoryReverseMismatch() {
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수").build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(999L).name("다른 카테고리").build())
                        .platformCategory(platformCategory).build()));
    }

    /** The whole cell-creation path: saves return ids so the graph below them is addressable. */
    private void givenSavesReturnIds() {
        given(productListingRepository.save(any())).willAnswer(inv ->
                ((ProductListing) inv.getArgument(0)).toBuilder().id(50L).build());
        given(productListingOptionRepository.save(any())).willAnswer(inv ->
                ((ProductListingOption) inv.getArgument(0)).toBuilder().id(60L).build());
    }

    private void verifyNothingSaved() {
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).save(any());
        verify(productListingProductRepository, never()).save(any());
        verify(masterProductOptionRepository, never()).save(any());
    }

    // ---- preview ----

    @Test
    void testPreviewReturnsOptionsAndComponents() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900"), marketOption("12입", "8124", "23900")));
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.empty());
        given(productRepository.findAllById(List.of(PRODUCT_A, PRODUCT_B)))
                .willReturn(List.of(product(PRODUCT_A), product(PRODUCT_B)));

        ListingImportPreviewResponse response = service.preview(MASTER_ID, previewRequest());

        assertThat(response.getProductName()).isEqualTo("노브랜드 생수 2L 6입");
        assertThat(response.getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(response.getOptions()).extracting(ListingImportPreviewResponse.Option::getItemName)
                .containsExactly("6입", "12입");
        assertThat(response.getOptions().get(0).getSalePrice()).isEqualByComparingTo("12900");
        assertThat(response.getComponents()).extracting(ListingImportPreviewResponse.Component::getProductId)
                .containsExactly(PRODUCT_A, PRODUCT_B);
        // D17: the master already carries "생수" → only the remainder becomes a channel tag.
        assertThat(response.getChannelTags()).containsExactly("2L");
        // 🔴 A preview must never write: the user has not entered the composition yet.
        verifyNothingSaved();
    }

    @Test
    void testPreviewCategoryUnmatchedReturnsWarning() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900")));
        givenCategoryReverseMismatch();
        given(productRepository.findAllById(List.of(PRODUCT_A, PRODUCT_B)))
                .willReturn(List.of(product(PRODUCT_A), product(PRODUCT_B)));

        ListingImportPreviewResponse response = service.preview(MASTER_ID, previewRequest());

        assertThat(response.isCategoryMatched()).isFalse();
        assertThat(response.getCategoryCode()).isEqualTo(COUPANG_CATEGORY);
        // The wording is the user's own — asserted verbatim so nobody "improves" it.
        assertThat(response.getCategoryWarning()).isEqualTo(
                "정확한 카테고리 매핑이 안되어 마스터 프로덕트의 카테고리가 적용되었습니다. "
                        + "채널에 반영될 때까지 카테고리별 수수료 차이로 인한 마진 오차가 발생할 수 있습니다.");
    }

    @Test
    void testPreviewMissingCategoryMappingThrows() {
        // 🔴 The FORWARD mapping is a different lookup from the D14 reverse one: a reverse mismatch only warns
        // (test above), a missing forward mapping blocks. Merging the two produces a quietly broken cell.
        givenMaster();
        givenForwardMapping(false);

        assertThatThrownBy(() -> service.preview(MASTER_ID, previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("카테고리 매핑 미설정");
        verifyNothingSaved();
    }

    @Test
    void testPreviewDuplicateChannelThrows() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, PLATFORM))
                .willReturn(true);

        // 409, not 400 — 03 branches on this status code.
        assertThatThrownBy(() -> service.preview(MASTER_ID, previewRequest()))
                .isInstanceOf(DuplicateChannelException.class);
        verifyNothingSaved();
    }

    @Test
    void testPreviewAlreadyImportedProductIdThrows() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        given(productListingRepository.existsByPlatformProductId(PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.preview(MASTER_ID, previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 다른 상품에 연결된");
        verifyNothingSaved();
    }

    // ---- commit ----

    @Test
    void testImportLinksExistingMasterOptionWhenBomMatches() {
        MasterProductOption existing = masterOption(10L, "6입");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        // D10: the composition matched → reuse, never create.
        verify(masterProductOptionRepository, never()).save(any());
        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        assertThat(captor.getValue().getMasterProductOption().getId()).isEqualTo(10L);
    }

    @Test
    void testImportCreatesMasterOptionWhenBomDiffers() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(1).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));
        given(masterProductOptionRepository.save(any())).willAnswer(inv ->
                ((MasterProductOption) inv.getArgument(0)).toBuilder().id(11L).build());

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<MasterProductOption> captor = ArgumentCaptor.forClass(MasterProductOption.class);
        verify(masterProductOptionRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("6입");         // D11
        verify(masterProductOptionItemRepository, times(2)).save(any());
        // Intended: the new master option is propagated to this master's OTHER cells.
        verify(masterOptionChannelSync).onOptionCreated(eq(MASTER_ID), any());
    }

    @Test
    void testImportKeepsCoupangNameAndPrice() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        ProductListingOption saved = captor.getValue();
        // 🔴 These four together are the regression guard: MANUAL_OVERRIDE on both axes is what makes
        // regenerateAssets leave the market's own name and price alone (D12/D13).
        assertThat(saved.getOptionName()).isEqualTo("6입");
        assertThat(saved.getOptionNameSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("12900");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
    }

    @Test
    void testImportStoresMarketIdsAndApproved() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        ProductListingOption saved = captor.getValue();
        assertThat(saved.getPlatformOptionId()).isEqualTo("8123");
        assertThat(saved.getSellerProductItemId()).isEqualTo("98123");
        // D20: an id exists ⇒ Coupang approved it, so [수정 요청] revises this product instead of creating one.
        assertThat(saved.getApprovalStatus()).isEqualTo(OptionApprovalStatus.APPROVED);
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void testImportStoresCoupangCategoryCodeAndChannelTags() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        ProductListing cell = captor.getValue();
        assertThat(cell.getPlatformCategoryCode()).isEqualTo(COUPANG_CATEGORY);          // D16
        assertThat(cell.getPlatformProductId()).isEqualTo(PRODUCT_ID);
        assertThat(cell.getStatus()).isEqualTo(ListingStatus.SELLING);                   // D19
        assertThat(cell.isNeedsMarketSync()).isFalse();
        // D17 is a difference, not a copy: a tag the master already has must NOT be stored on the channel too.
        assertThat(cell.getTags()).containsExactly("2L");
    }

    @Test
    void testImportRejectsMissingComponent() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900")));

        // D9: the master has two components; sending only one must fail before anything is written.
        assertThatThrownBy(() -> service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빠진 구성상품");
        verifyNothingSaved();
    }

    @Test
    void testImportRejectsChangedCoupangOptions() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        // The re-read shows an option the request does not know about → the preview is stale.
        givenMarket(marketProduct(marketOption("6입", "8123", "12900"), marketOption("12입", "8124", "23900")));

        assertThatThrownBy(() -> service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("쿠팡 옵션이 변경되었습니다");
        verifyNothingSaved();
    }

    // ---- asset generation (runs AFTER the cell is committed) ----

    /** The normal path: the cell is committed, then assets are generated exactly once. */
    @Test
    void testImportGeneratesAssetsAfterCommit() {
        MasterProductOption existing = masterOption(10L, "6입");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        ChannelAddResponse response = service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        verify(listingAssetService).regenerate(50L);
        assertThat(response.getAssetsGenerated()).isTrue();
        assertThat(response.getProductListingId()).isEqualTo(50L);
    }

    /**
     * 🔴 Regression guard: a product with no photo makes asset generation throw 400, and that must NOT
     * take the cell/options/BOM with it — the user fills the photo in later and hits [재생성].
     */
    @Test
    void testImportAssetFailureKeepsCellOptionsAndBom() {
        MasterProductOption existing = masterOption(10L, "6입");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));
        willThrow(new IllegalArgumentException("상품 이미지를 불러올 수 없습니다: 이미지가 없습니다"))
                .given(listingAssetService).regenerate(50L);

        ChannelAddResponse response = service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        // No exception escaped, and the cell graph was written.
        assertThat(response.getAssetsGenerated()).isFalse();
        assertThat(response.getProductListingId()).isEqualTo(50L);
        verify(productListingRepository).save(any());
        verify(productListingOptionRepository).save(any());
        verify(productListingProductRepository, times(2)).save(any());
    }

    /**
     * 🔴 The catch is narrow on purpose: everything that is NOT asset generation (stale options, missing
     * component, duplicate channel, missing mapping…) must still blow up and roll the cell back.
     */
    @Test
    void testImportNonAssetFailureStillFails() {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(marketProduct(marketOption("6입", "8123", "12900"), marketOption("12입", "8124", "23900")));

        assertThatThrownBy(() -> service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("쿠팡 옵션이 변경되었습니다");
        verifyNothingSaved();
        // The commit never reached the asset step, so nothing was generated either.
        verify(listingAssetService, never()).regenerate(anyLong());
    }

    /** Everything a successful commit needs: guards pass, the market answers, and saves hand back ids. */
    private void givenImportReady(ImportedProduct product, List<MasterProductOption> masterOptions,
                                  List<MasterProductOptionItem> items) {
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(product);
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.empty());
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(masterOptions);
        given(masterProductOptionItemRepository.findByOptionIdIn(
                masterOptions.stream().map(MasterProductOption::getId).toList())).willReturn(items);
        givenSavesReturnIds();
    }

    // 2609_39/D19 ④: 편입 옵션의 가격은 마켓에서 <b>읽어온</b> 실가격이라 market_price 와 selling_price 가
    // 같은 값으로 출발한다. 이걸 빼면 온보딩한 셀이 첫날부터 「아직 안 밀림」으로 잘못 쌓인다.
    @Test
    void testImportRecordsMarketPriceFromMarket() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenImportReady(marketProduct(marketOption("6입", "8123", "12900")), List.of(existing),
                List.of(MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                        MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> captor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(captor.capture());
        ProductListingOption saved = captor.getValue();
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("12900");
        assertThat(saved.getMarketPrice()).isEqualByComparingTo(saved.getSellingPrice());
        assertThat(saved.getMarketPriceAt()).isNotNull();
    }

    // ---- 2609_45/D12: cell-scoped category meta ----

    private ImportedProduct.Option marketOptionWithMeta(String name, String vendorItemId, String salePrice) {
        return new ImportedProduct.Option(name, vendorItemId, "9" + vendorItemId,
                new BigDecimal(salePrice), new BigDecimal(salePrice).add(BigDecimal.valueOf(3000)), 50,
                java.util.Map.of("수량", "6"), java.util.Map.of("품목 또는 명칭", "쌀"));
    }

    private ImportedProduct marketProductWithMeta() {
        return new ImportedProduct("노브랜드 생수 2L 6입", COUPANG_CATEGORY, ListingStatus.SELLING,
                List.of("생수", "2L"), "가공식품", List.of(marketOptionWithMeta("6입", "8123", "12900")));
    }

    /** Guards pass, the market answers with per-option meta, saves hand back ids. */
    private void givenImportReadyWithMeta() {
        MasterProductOption existing = masterOption(10L, "1세트");
        givenMaster();
        givenForwardMapping(true);
        givenAccount();
        givenNoDuplicateChannel();
        givenMarket(marketProductWithMeta());
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(existing));
        given(masterProductOptionItemRepository.findByOptionIdIn(List.of(10L))).willReturn(List.of(
                MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_A)).quantity(6).build(),
                MasterProductOptionItem.builder().option(existing).product(product(PRODUCT_B)).quantity(1).build()));
        givenSavesReturnIds();
    }

    /** Reverse lookup resolves to the SAME standard category the master uses → the categories match. */
    private void givenCategoryReverseMatch(String commissionRate) {
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수")
                .commissionRate(commissionRate == null ? null : new BigDecimal(commissionRate)).build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(CATEGORY_ID).name("생수").build())
                        .platformCategory(platformCategory).build()));
    }

    /** Reverse lookup resolves to a DIFFERENT standard category, and that category carries a commission. */
    private void givenCategoryReverseMismatch(String commissionRate) {
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수")
                .commissionRate(commissionRate == null ? null : new BigDecimal(commissionRate)).build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(999L).name("다른 카테고리").build())
                        .platformCategory(platformCategory).build()));
    }

    // 7. Same category → the master's values ARE the answer; copying them would create a second, diverging copy.
    @Test
    void testImportSameCategoryLeavesCellMetaNull() {
        givenImportReadyWithMeta();
        givenCategoryReverseMatch("0.11");

        service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getCategoryAttributes()).isNull();
        assertThat(optionCaptor.getValue().getCategoryNotices()).isNull();

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getCategoryNoticeGroup()).isNull();
    }

    // 8. Different category WITH a commission → the cell keeps its own category, so it carries the whole
    //    per-option attribute map (D12-1 removes the master values from the merge base) — and no warning.
    @Test
    void testImportDifferentCategoryWithCommissionStoresCellMeta() {
        givenImportReadyWithMeta();
        givenCategoryReverseMismatch("0.11");

        var response = service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getCategoryAttributes()).containsEntry("수량", "6");
        assertThat(optionCaptor.getValue().getCategoryNotices()).containsEntry("품목 또는 명칭", "쌀");

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getPlatformCategoryCode()).isEqualTo(COUPANG_CATEGORY);
        assertThat(cellCaptor.getValue().getCategoryNoticeGroup()).isEqualTo("가공식품");
        // D15 문구는 이제 "수수료 없음"에만 붙는다 — 불일치 자체는 경고가 아니다(채널이 자기 것을 유지한다).
        assertThat(response.getCategoryWarning()).isNull();
    }

    // 9. Different category WITHOUT a commission (D11) → resolution falls back to the master, so leaving the
    //    cell meta behind would mean values of a schema this cell no longer uses. The D15 warning fires here.
    @Test
    void testImportDifferentCategoryWithoutCommissionFallsBackAndWarns() {
        givenImportReadyWithMeta();
        givenCategoryReverseMismatch(null);

        var response = service.importListing(MASTER_ID, importRequest(spec("6입", "8123", 6, 1)));

        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getCategoryAttributes()).isNull();
        assertThat(optionCaptor.getValue().getCategoryNotices()).isNull();

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        // 표시용 코드는 그대로 저장하되 셀 메타·그룹은 넣지 않는다.
        assertThat(cellCaptor.getValue().getPlatformCategoryCode()).isEqualTo(COUPANG_CATEGORY);
        assertThat(cellCaptor.getValue().getCategoryNoticeGroup()).isNull();
        assertThat(response.getCategoryWarning())
                .isEqualTo(CoupangListingImportServiceImpl.CATEGORY_WARNING);
    }
}
