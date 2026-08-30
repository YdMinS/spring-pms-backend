package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.response.ChannelSyncPreviewResponse;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.exception.ValidationException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.listing.OptionCheckSuffix;
import com.pms.service.listing.MasterOptionChannelSync;
import com.pms.service.listing.OptionQuantitySync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.pms.service.listing.shipping.ShippingOverrideKeys;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Coverage-matrix mapping (accounts LEFT JOIN listings, N+1 guard, 404) + master/option definition:
 * option coverage validation (full component set, quantity ≥ 1) and the component-change re-validation.
 */
@ExtendWith(MockitoExtension.class)
class MasterProductServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductComponentRepository componentRepository;
    @Mock private MasterProductOptionRepository optionRepository;
    @Mock private MasterProductOptionItemRepository optionItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private CarrierRateRepository carrierRateRepository;
    @Mock private PackageRepository packageRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private ListingAssetService listingAssetService;
    @Mock private OptionQuantitySync optionQuantitySync;
    @Mock private MasterOptionChannelSync masterOptionChannelSync;
    @InjectMocks private MasterProductServiceImpl service;

    private Seller seller(Long id, String name) {
        return Seller.builder().id(id).sellerName(name).businessRegistration(id + "-x").build();
    }

    private MarketplaceAccount account(Long id, Seller seller, String platform, String alias) {
        return MarketplaceAccount.builder()
                .id(id).seller(seller).platform(platform).accountAlias(alias).build();
    }

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).build();
    }

    /** 100: a product carrying the measured amount (98 netContent/netContentUnit). */
    private Product product(Long id, String name, String netContent, String netContentUnit) {
        return Product.builder().id(id).productName(name)
                .netContent(netContent).netContentUnit(netContentUnit).build();
    }

    private MasterProductComponent component(MasterProduct master, Product product) {
        return MasterProductComponent.builder().masterProduct(master).product(product).build();
    }

    private MasterOptionRequest.OptionItem item(Long productId, Integer quantity) {
        return MasterOptionRequest.OptionItem.builder().productId(productId).quantity(quantity).build();
    }

    @Test
    void getMatrix_mapsAccountsAgainstListings() {
        Seller seller1 = seller(1L, "판매자1");
        Seller seller2 = seller(2L, "판매자2");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();

        MarketplaceAccount acc1 = account(10L, seller1, "COUPANG", "메인");
        MarketplaceAccount acc2 = account(11L, seller1, "NAVER", null);
        MarketplaceAccount acc3 = account(12L, seller2, "COUPANG", "서브");

        ProductListing listing = ProductListing.builder()
                .id(100L).seller(seller1).platform("COUPANG").platformProductId("X").name("리스팅").build();
        ProductListingOption option = ProductListingOption.builder()
                .productListing(listing).optionName("SKU").sellingPrice(new BigDecimal("1000")).build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(marketplaceAccountRepository.findAll()).willReturn(List.of(acc1, acc2, acc3));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(listing));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(option));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller1, seller2));

        ListingMatrixResponse matrix = service.getMatrix(1L);

        assertThat(matrix.getMasterId()).isEqualTo(1L);
        assertThat(matrix.getRows()).hasSize(3);

        ListingMatrixResponse.MatrixRow row1 = matrix.getRows().get(0);
        assertThat(row1.isRegistered()).isTrue();
        assertThat(row1.getSellerName()).isEqualTo("판매자1");
        assertThat(row1.getAccountLabel()).isEqualTo("메인");
        assertThat(row1.getCell().getProductListingId()).isEqualTo(100L);
        assertThat(row1.getCell().getName()).isEqualTo("리스팅");   // 노출상품명 = listing name (35)
        assertThat(row1.getCell().getPlatformProductId()).isEqualTo("X");
        assertThat(row1.getCell().getSellingPrice()).isEqualByComparingTo("1000");

        assertThat(matrix.getRows().get(1).isRegistered()).isFalse();     // acc2 seller1/NAVER
        assertThat(matrix.getRows().get(1).getCell()).isNull();
        assertThat(matrix.getRows().get(2).isRegistered()).isFalse();     // acc3 seller2/COUPANG
        assertThat(matrix.getRows().get(2).getCell()).isNull();

        // N+1 guard: listings fetched exactly once.
        verify(productListingRepository, times(1)).findByMasterProductId(1L);
    }

    @Test
    void getMatrix_registrationNameIsGeneratedPerChannelFromActiveOptions() {
        // 67: the registration name differs per channel — listing A has 1 active option (single form), listing B
        // has 2 (옵션확인 form). The master options are loaded exactly ONCE (not per cell → N+1 guard).
        Seller seller1 = seller(1L, "판매자1");
        Seller seller2 = seller(2L, "판매자2");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();

        MarketplaceAccount acc1 = account(10L, seller1, "COUPANG", "메인");   // listing A: 1 active option
        MarketplaceAccount acc2 = account(12L, seller2, "COUPANG", "서브");   // listing B: 2 active options

        ProductListing listingA = ProductListing.builder()
                .id(100L).seller(seller1).platform("COUPANG").platformProductId("X").name("리스팅1").build();
        ProductListing listingB = ProductListing.builder()
                .id(101L).seller(seller2).platform("COUPANG").platformProductId("Y").name("리스팅2").build();

        ProductListingOption a1 = ProductListingOption.builder().id(1L).productListing(listingA)
                .optionName("1세트").sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption b1 = ProductListingOption.builder().id(2L).productListing(listingB)
                .optionName("1세트").sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption b2 = ProductListingOption.builder().id(3L).productListing(listingB)
                .optionName("2세트").sellingPrice(new BigDecimal("12000")).active(true).build();

        List<MasterProductOption> masterOptions = List.of(
                MasterProductOption.builder().id(5L).name("1세트").build(),
                MasterProductOption.builder().id(6L).name("2세트").build());

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(masterOptions);
        given(marketplaceAccountRepository.findAll()).willReturn(List.of(acc1, acc2));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(listingA, listingB));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(a1, b1, b2));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller1, seller2));
        // Generator is driven by each listing's active option-name set (A={1세트}, B={1세트,2세트}).
        given(registrationNameGenerator.generate(eq(master), eq(java.util.Set.of("1세트")), any(), any()))
                .willReturn("노브랜드 생수 x 6");
        given(registrationNameGenerator.generate(eq(master), eq(java.util.Set.of("1세트", "2세트")), any(), any()))
                .willReturn("노브랜드 생수, 다우니 섬유유연제 - 옵션확인");

        ListingMatrixResponse matrix = service.getMatrix(1L);

        assertThat(matrix.getRows().get(0).getCell().getRegistrationName()).isEqualTo("노브랜드 생수 x 6");
        assertThat(matrix.getRows().get(1).getCell().getRegistrationName())
                .isEqualTo("노브랜드 생수, 다우니 섬유유연제 - 옵션확인");

        // Master options loaded once (N+1 guard) — the generator re-reads no options from the repo.
        verify(optionRepository, times(1)).findByMasterProductId(1L);
    }

    @Test
    void getMatrix_missingMaster_throws404() {
        given(masterProductRepository.findScopedById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMatrix(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------- list / soft delete

    @Test
    void getMasterProducts_returnsOnlyActive_excludesSoftDeleted() {
        // Repository filters active=true, so a soft-deleted master never reaches the response.
        given(masterProductRepository.findByActiveTrue())
                .willReturn(List.of(MasterProduct.builder().id(1L).name("활성").active(true).build()));

        List<MasterProductResponse> result = service.getMasterProducts();

        assertThat(result).extracting(MasterProductResponse::getId).containsExactly(1L);
        // List path does NOT call the generator (N+1 guard) → registrationName stays null.
        assertThat(result.get(0).getRegistrationName()).isNull();
        verify(registrationNameGenerator, never()).generate(any(), any());
        verify(masterProductRepository, never()).findAll();
    }

    @Test
    void getMasterProducts_overlaysSourceMappingCover() {
        // The __source__ pool mapping (37) wins over the legacy sourceImageUrl in the list thumbnail.
        given(masterProductRepository.findByActiveTrue())
                .willReturn(List.of(MasterProduct.builder()
                        .id(7L).name("커버").active(true).sourceImageUrl("legacy.jpg").build()));
        given(masterImageZoneAssignmentRepository.findZoneImageUrlsByMasterIds(
                MasterImageZoneAssignment.SOURCE_ZONE, List.of(7L)))
                .willReturn(List.<Object[]>of(new Object[] { 7L, "https://cdn/mapped.jpg" }));

        List<MasterProductResponse> result = service.getMasterProducts();

        assertThat(result.get(0).getSourceImageUrl()).isEqualTo("https://cdn/mapped.jpg");
    }

    @Test
    void getMasterProduct_singlePath_exposesRegistrationName() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(registrationNameGenerator.generate(eq(master), any())).willReturn("노브랜드 생수 x 6");

        MasterProductResponse response = service.getMasterProduct(1L);

        assertThat(response.getRegistrationName()).isEqualTo("노브랜드 생수 x 6");
        // 69: master-level uses resolveForMaster (master ?? system — no channel/seller context).
        verify(optionCheckSuffixResolver).resolveForMaster(master);
    }

    // 100: the component the master holds (`bare`) is deliberately EMPTY while the batch map returns the
    // populated product (`full`). Reading the amount off c.getProduct() (the lazy proxy) therefore yields
    // null and fails — the whole point of keeping the batched Map<Long, Product> as the single source.
    @Test
    void getMasterProduct_exposesComponentNetContent() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        Product bare = product(1L, "NST 녹차라떼");
        Product full = product(1L, "NST 녹차라떼", "320", "G");
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of(component(master, bare)));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productRepository.findAllById(any())).willReturn(List.of(full));

        MasterProductResponse response = service.getMasterProduct(1L);

        MasterProductResponse.Component c = response.getComponents().get(0);
        assertThat(c.getProductName()).isEqualTo("NST 녹차라떼");
        // Raw, unformatted — the client (101) decides how to render "320" + "G".
        assertThat(c.getNetContent()).isEqualTo("320");
        assertThat(c.getNetContentUnit()).isEqualTo("G");
    }

    @Test
    void getMasterProduct_nullNetContentWhenProductHasNone() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        Product bare = product(1L, "NST 녹차라떼");
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of(component(master, bare)));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productRepository.findAllById(any())).willReturn(List.of(bare));

        MasterProductResponse response = service.getMasterProduct(1L);

        MasterProductResponse.Component c = response.getComponents().get(0);
        // null, never "" — 101 has to be able to tell "no value" apart from an empty amount.
        assertThat(c.getNetContent()).isNull();
        assertThat(c.getNetContentUnit()).isNull();
    }

    @Test
    void getMasterProduct_singleQueryForProducts() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        Product p1 = product(1L, "물품1", "320", "G");
        Product p2 = product(2L, "물품2", "500", "ML");
        Product p3 = product(3L, "물품3", null, null);
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of(
                component(master, product(1L, "물품1")),
                component(master, product(2L, "물품2")),
                component(master, product(3L, "물품3"))));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productRepository.findAllById(any())).willReturn(List.of(p1, p2, p3));

        service.getMasterProduct(1L);

        // N+1 guard: three components, still one batched fetch.
        verify(productRepository, times(1)).findAllById(any());
    }

    @Test
    void getMatrix_suffixResolvedPerChannel_usesPureOverloadNoAccountRequery() {
        // 69: the suffix is resolved per channel via the PURE resolve(account, master, seller) overload (reusing
        // the already-loaded matrix rows) — never resolve(cell), which would re-query the account per cell.
        Seller seller1 = seller(1L, "판매자1");
        Seller seller2 = seller(2L, "판매자2");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();

        MarketplaceAccount acc1 = account(10L, seller1, "COUPANG", "메인");   // channel override: suffix OFF
        MarketplaceAccount acc2 = account(12L, seller2, "COUPANG", "서브");   // default: suffix ON

        ProductListing listingA = ProductListing.builder()
                .id(100L).seller(seller1).platform("COUPANG").platformProductId("X").name("리스팅1").build();
        ProductListing listingB = ProductListing.builder()
                .id(101L).seller(seller2).platform("COUPANG").platformProductId("Y").name("리스팅2").build();
        ProductListingOption a1 = ProductListingOption.builder().id(1L).productListing(listingA)
                .optionName("1세트").sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption a2 = ProductListingOption.builder().id(2L).productListing(listingA)
                .optionName("2세트").sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption b1 = ProductListingOption.builder().id(3L).productListing(listingB)
                .optionName("1세트").sellingPrice(new BigDecimal("6000")).active(true).build();
        ProductListingOption b2 = ProductListingOption.builder().id(4L).productListing(listingB)
                .optionName("2세트").sellingPrice(new BigDecimal("6000")).active(true).build();

        OptionCheckSuffix off = new OptionCheckSuffix(false, "옵션확인");
        OptionCheckSuffix on = new OptionCheckSuffix(true, "옵션확인");

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductOption.builder().id(5L).name("1세트").build(),
                MasterProductOption.builder().id(6L).name("2세트").build()));
        given(marketplaceAccountRepository.findAll()).willReturn(List.of(acc1, acc2));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(listingA, listingB));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(a1, a2, b1, b2));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller1, seller2));
        given(optionCheckSuffixResolver.resolve(eq(acc1), eq(master), any())).willReturn(off);
        given(optionCheckSuffixResolver.resolve(eq(acc2), eq(master), any())).willReturn(on);
        given(registrationNameGenerator.generate(eq(master), any(), any(), eq(off))).willReturn("구성A");
        given(registrationNameGenerator.generate(eq(master), any(), any(), eq(on))).willReturn("구성A - 옵션확인");

        ListingMatrixResponse matrix = service.getMatrix(1L);

        assertThat(matrix.getRows().get(0).getCell().getRegistrationName()).isEqualTo("구성A");
        assertThat(matrix.getRows().get(1).getCell().getRegistrationName()).isEqualTo("구성A - 옵션확인");
        // Pure overload only — the per-cell resolve(cell) (account re-query) is never used.
        verify(optionCheckSuffixResolver, never()).resolve(any(ProductListing.class));
    }

    @Test
    void updateRegistrationNameSuffix_savesReplaceValues_blankSuffixToNull() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(masterProductRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());

        // enabled=false + blank suffix → suffix normalized to null (inherit).
        service.updateRegistrationNameSuffix(1L, com.pms.dto.request.OptionCheckSuffixRequest.builder()
                .enabled(false).suffix("   ").build());

        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(captor.capture());
        assertThat(captor.getValue().getOptionCheckSuffixEnabled()).isFalse();
        assertThat(captor.getValue().getOptionCheckSuffix()).isNull();
    }

    @Test
    void deleteMasterProduct_noOnMarketCells_softDeletesSetsActiveFalse() {
        given(masterProductRepository.findScopedById(1L))
                .willReturn(Optional.of(MasterProduct.builder().id(1L).name("마스터A").active(true).build()));
        // Only a DRAFT (off-market, platformProductId == null) cell → delete allowed.
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(
                ProductListing.builder().id(100L).platform("COUPANG").platformProductId(null).build()));

        service.deleteMasterProduct(1L);

        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(captor.capture());
        assertThat(captor.getValue().getActive()).isFalse();
    }

    @Test
    void deleteMasterProduct_onMarketCell_throws409AndDoesNotSave() {
        given(masterProductRepository.findScopedById(1L))
                .willReturn(Optional.of(MasterProduct.builder().id(1L).name("마스터A").active(true).build()));
        // An on-market cell (platformProductId != null) blocks deletion.
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(
                ProductListing.builder().id(100L).platform("COUPANG").platformProductId("CP-1").build()));

        assertThatThrownBy(() -> service.deleteMasterProduct(1L))
                .isInstanceOf(MasterProductInUseException.class);
        verify(masterProductRepository, never()).save(any());
    }

    // ------------------------------------------------------------- master create

    @Test
    void createMasterProduct_happy_savesComponents() {
        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L, 2L))
                .options(List.of(MasterOptionRequest.builder()
                        .name("1세트").items(List.of(item(1L, 1), item(2L, 1))).build()))
                .build();
        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));
        given(masterProductRepository.save(any()))
                .willReturn(MasterProduct.builder().id(5L).name("마스터A").active(true).build());
        given(optionRepository.save(any()))
                .willReturn(MasterProductOption.builder().id(10L).name("1세트").build());
        // mapToResponse re-reads the (empty) component/option sets of the saved master
        given(componentRepository.findByMasterProductId(5L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(5L)).willReturn(List.of());

        MasterProductResponse response = service.createMasterProduct(request);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getActive()).isTrue();
        // one component row saved per requested product
        verify(componentRepository, times(2)).save(any());
    }

    // ------------------------------------------------------------- master + options atomic create (27)

    @Test
    void createMasterProduct_withOptions_savesMasterComponentsOptions() {
        MasterProduct saved = MasterProduct.builder().id(5L).name("마스터A").active(true).build();
        MasterProductOption option = MasterProductOption.builder().id(10L).masterProduct(saved).name("2세트").build();

        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));
        given(masterProductRepository.save(any())).willReturn(saved);
        given(optionRepository.save(any())).willReturn(option);
        // mapToResponse re-reads the saved master's components + options + items
        given(componentRepository.findByMasterProductId(5L))
                .willReturn(List.of(component(saved, product(1L, "상품1")), component(saved, product(2L, "상품2"))));
        given(optionRepository.findByMasterProductId(5L)).willReturn(List.of(option));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(
                MasterProductOptionItem.builder().option(option).product(product(1L, "상품1")).quantity(2).build(),
                MasterProductOptionItem.builder().option(option).product(product(2L, "상품2")).quantity(2).build()));

        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L, 2L))
                .options(List.of(MasterOptionRequest.builder()
                        .name("2세트").items(List.of(item(1L, 2), item(2L, 2))).build()))
                .build();

        MasterProductResponse response = service.createMasterProduct(request);

        verify(masterProductRepository, times(1)).save(any());
        verify(componentRepository, times(2)).save(any());
        verify(optionRepository, times(1)).save(any());
        verify(optionItemRepository, times(2)).save(any());
        assertThat(response.getOptions()).hasSize(1);
    }

    @Test
    void createMasterProduct_invalidOption_throws400AndDoesNotSaveMaster() {
        // components {1, 2} but the option omits product 2 → subset. Pre-validation aborts before any save.
        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));

        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L, 2L))
                .options(List.of(MasterOptionRequest.builder()
                        .name("불완전").items(List.of(item(1L, 2))).build()))
                .build();

        assertThatThrownBy(() -> service.createMasterProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("옵션은 구성상품 전체를 포함해야 합니다");
        // Atomicity: neither the master nor any option was persisted.
        verify(masterProductRepository, never()).save(any());
        verify(optionRepository, never()).save(any());
    }

    @Test
    void createMasterProduct_noOptions_throws400AndDoesNotSaveMaster() {
        // 84: a master must always carry at least one option — the old "master-only" allowance is closed.
        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L)).build();   // options == null
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        assertThatThrownBy(() -> service.createMasterProduct(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("옵션을 1개 이상 등록하세요.");
        verify(masterProductRepository, never()).save(any());
        verify(optionRepository, never()).save(any());
    }

    @Test
    void createMasterProduct_duplicateOptionNameInArray_throws400AndDoesNotSaveMaster() {
        // 86: assertNameUnique guards createOption/updateOption; the array posted at master creation was the
        // remaining hole — same rule, same message, same trim (" 2세트 " == "2세트").
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L))
                .options(List.of(
                        MasterOptionRequest.builder().name("2세트").items(List.of(item(1L, 2))).build(),
                        MasterOptionRequest.builder().name(" 2세트 ").items(List.of(item(1L, 3))).build()))
                .build();

        assertThatThrownBy(() -> service.createMasterProduct(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("같은 이름의 옵션이 이미 있습니다.");
        // Pre-validation: aborts before any save (no half-written master).
        verify(masterProductRepository, never()).save(any());
        verify(optionRepository, never()).save(any());
    }

    // ------------------------------------------------------------- option coverage validation

    @Test
    void createOption_fullCoverage_savesOptionAndItems() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1")), component(master, product(2L, "상품2"))));
        given(optionRepository.save(any()))
                .willReturn(MasterProductOption.builder().id(10L).masterProduct(master).name("2세트").build());
        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));

        MasterOptionRequest request = MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 2), item(2L, 2))).build();
        service.createOption(1L, request);

        verify(optionRepository, times(1)).save(any());
        // one item row saved per component product; captured products cover the full component set
        ArgumentCaptor<MasterProductOptionItem> captor = ArgumentCaptor.forClass(MasterProductOptionItem.class);
        verify(optionItemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().stream().map(it -> it.getProduct().getId()))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void createOption_missingComponentProduct_throws400AndDoesNotSave() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1")), component(master, product(2L, "상품2"))));

        // items omit product 2 → subset of the component set
        MasterOptionRequest request = MasterOptionRequest.builder()
                .name("불완전").items(List.of(item(1L, 1))).build();

        assertThatThrownBy(() -> service.createOption(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("옵션은 구성상품 전체를 포함해야 합니다");
        verify(optionRepository, never()).save(any());
    }

    @Test
    void createOption_zeroQuantity_throws400AndDoesNotSave() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1")), component(master, product(2L, "상품2"))));

        // full coverage but quantity 0 on product 2
        MasterOptionRequest request = MasterOptionRequest.builder()
                .name("영수량").items(List.of(item(1L, 1), item(2L, 0))).build();

        assertThatThrownBy(() -> service.createOption(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수량은 1 이상");
        verify(optionRepository, never()).save(any());
    }

    // ------------------------------------------------------------- component change re-validation

    @Test
    void updateMasterProduct_componentChangeBreaksExistingOption_throws400() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(masterProductRepository.save(any())).willReturn(master);
        // new component set = {1, 2}
        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));
        // an existing option only covers product 1 → no longer covers the new set
        MasterProductOption option = MasterProductOption.builder().id(10L).masterProduct(master).name("기존").build();
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(option));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(
                MasterProductOptionItem.builder().option(option).product(product(1L, "상품1")).quantity(1).build()));

        MasterProductUpdateRequest request = MasterProductUpdateRequest.builder()
                .componentProductIds(List.of(1L, 2L)).build();

        assertThatThrownBy(() -> service.updateMasterProduct(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("구성 변경이 기존 옵션과 불일치");
    }

    // ------------------------------------------------------------- master default delivery/box (13)

    @Test
    void createMasterProduct_withDefaults_setsDeliveryAndPackage() {
        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L))
                .defaultDeliveryId(4L).defaultPackageId(5L)
                .options(List.of(MasterOptionRequest.builder()
                        .name("1세트").items(List.of(item(1L, 1))).build()))
                .build();
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));
        given(optionRepository.save(any()))
                .willReturn(MasterProductOption.builder().id(10L).name("1세트").build());
        given(carrierRateRepository.findById(4L)).willReturn(Optional.of(CarrierRate.builder().id(4L).build()));
        given(packageRepository.findById(5L)).willReturn(Optional.of(Package.builder().id(5L).build()));
        given(masterProductRepository.save(any()))
                .willAnswer(inv -> ((MasterProduct) inv.getArgument(0)).toBuilder().id(5L).build());
        given(componentRepository.findByMasterProductId(5L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(5L)).willReturn(List.of());

        service.createMasterProduct(request);

        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(captor.capture());
        assertThat(captor.getValue().getDefaultDelivery().getId()).isEqualTo(4L);
        assertThat(captor.getValue().getDefaultPackage().getId()).isEqualTo(5L);
    }

    // ------------------------------------------------------------- option delivery/box override (13)

    @Test
    void createOption_withOverride_exposesDeliveryAndPackageIds() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1"))));
        given(carrierRateRepository.findById(4L)).willReturn(Optional.of(CarrierRate.builder().id(4L).build()));
        given(packageRepository.findById(5L)).willReturn(Optional.of(Package.builder().id(5L).build()));
        given(optionRepository.save(any()))
                .willAnswer(inv -> ((MasterProductOption) inv.getArgument(0)).toBuilder().id(10L).build());
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        MasterOptionResponse resp = service.createOption(1L, MasterOptionRequest.builder()
                .name("1세트").items(List.of(item(1L, 1))).deliveryId(4L).packageId(5L).build());

        assertThat(resp.getDeliveryId()).isEqualTo(4L);
        assertThat(resp.getPackageId()).isEqualTo(5L);
    }

    // ------------------------------------------------------------- option category-meta override (59)

    @Test
    void createOption_withCategoryMetaOverride_savesAndExposesMaps() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1"))));
        given(optionRepository.save(any()))
                .willAnswer(inv -> ((MasterProductOption) inv.getArgument(0)).toBuilder().id(10L).build());
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        MasterOptionResponse resp = service.createOption(1L, MasterOptionRequest.builder()
                .name("30포").items(List.of(item(1L, 1)))
                .categoryAttributes(java.util.Map.of("개당중량", "30g"))
                .categoryNotices(java.util.Map.of("용량", "30포")).build());

        // saved with the override maps...
        ArgumentCaptor<MasterProductOption> captor = ArgumentCaptor.forClass(MasterProductOption.class);
        verify(optionRepository).save(captor.capture());
        assertThat(captor.getValue().getCategoryAttributes()).containsEntry("개당중량", "30g");
        assertThat(captor.getValue().getCategoryNotices()).containsEntry("용량", "30포");
        // ...and echoed back in the response (prefill for the option editor).
        assertThat(resp.getCategoryAttributes()).containsEntry("개당중량", "30g");
        assertThat(resp.getCategoryNotices()).containsEntry("용량", "30포");
    }

    @Test
    void updateOption_nullCategoryMeta_keepsExistingOverride() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        MasterProductOption existing = MasterProductOption.builder().id(10L).masterProduct(master).name("30포")
                .categoryAttributes(java.util.Map.of("개당중량", "30g")).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findById(10L)).willReturn(Optional.of(existing));
        given(optionItemRepository.findByOptionId(10L)).willReturn(List.of(
                MasterProductOptionItem.builder().option(existing).product(product(1L, "상품1")).quantity(1).build()));
        given(optionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        // name-only update (no items, no category maps) → existing override kept.
        service.updateOption(1L, 10L, MasterOptionRequest.builder().name("30포-수정").build());

        ArgumentCaptor<MasterProductOption> captor = ArgumentCaptor.forClass(MasterProductOption.class);
        verify(optionRepository).save(captor.capture());
        assertThat(captor.getValue().getCategoryAttributes()).containsEntry("개당중량", "30g");
    }

    // ------------------------------------------------------------- standard category (single, 44)

    @Test
    void setCategory_leafMappedToCoupang_savesMasterStandardCategory() {
        // 52: a master may only pick a selectable leaf that is mapped to Coupang.
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(categoryRepository.findById(3L))
                .willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(categoryRepository.existsByParentId(3L)).willReturn(false);            // leaf
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(3L, "COUPANG")).willReturn(true);
        given(masterProductRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MasterCategoryResponse resp = service.setCategory(1L,
                MasterCategoryRequest.builder().categoryId(3L).build());

        assertThat(resp.getCategoryId()).isEqualTo(3L);
        assertThat(resp.getCategoryName()).isEqualTo("신발");
        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory().getId()).isEqualTo(3L);   // master now points to the category
    }

    @Test
    void setCategory_nonLeaf_throws400() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(categoryRepository.findById(3L))
                .willReturn(Optional.of(Category.builder().id(3L).name("의류").build()));
        given(categoryRepository.existsByParentId(3L)).willReturn(true);             // has children = not leaf

        assertThatThrownBy(() -> service.setCategory(1L, MasterCategoryRequest.builder().categoryId(3L).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("세부(leaf) 카테고리만 지정할 수 있습니다.");
        verify(masterProductRepository, never()).save(any());
    }

    @Test
    void setCategory_leafWithoutCoupangMapping_throws400() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(categoryRepository.findById(3L))
                .willReturn(Optional.of(Category.builder().id(3L).name("신발").build()));
        given(categoryRepository.existsByParentId(3L)).willReturn(false);            // leaf
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(3L, "COUPANG")).willReturn(false);

        assertThatThrownBy(() -> service.setCategory(1L, MasterCategoryRequest.builder().categoryId(3L).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("쿠팡 카테고리 매핑이 없습니다.");
        verify(masterProductRepository, never()).save(any());
    }

    @Test
    void setCategory_categoryNotFound_throws404() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.setCategory(1L, MasterCategoryRequest.builder().categoryId(99L).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getCategory_unset_returnsNullFields() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).category(null).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));

        MasterCategoryResponse resp = service.getCategory(1L);

        assertThat(resp.getCategoryId()).isNull();
        assertThat(resp.getCategoryName()).isNull();
    }

    // ------------------------------------------------- 77/79: force-apply (overwrite) to channels

    @Test
    void applyShippingOverrideToChannels_overwritesCellWithMasterValues_keepsPlaceKeys_countsChangedOnly() {
        // Given a master that sets a master-level key, cell A overriding it differently (+ a place key),
        // and cell B already carrying exactly the master's value
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true)
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "SEQUENCIAL")).build();
        Map<String, String> cellAOverride = new LinkedHashMap<>();
        cellAOverride.put(ShippingOverrideKeys.DELIVERY_METHOD, "MAKE_ORDER");
        cellAOverride.put(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "OUT-1");
        ProductListing cellA = ProductListing.builder().id(100L).name("셀A").shippingOverride(cellAOverride).build();
        ProductListing cellB = ProductListing.builder().id(101L).name("셀B")
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "SEQUENCIAL")).build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cellA, cellB));

        // When
        int affected = service.applyShippingOverrideToChannels(1L, null);

        // Then only cell A changed: the master's value is written onto it, the place key survives.
        // Cell B already equals the master → not saved, not counted (idempotent).
        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<List<ProductListing>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingRepository).saveAll(captor.capture());
        List<ProductListing> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isEqualTo(100L);
        assertThat(saved.get(0).getShippingOverride())
                .containsEntry(ShippingOverrideKeys.DELIVERY_METHOD, "SEQUENCIAL")
                .containsEntry(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "OUT-1");
    }

    @Test
    void applyShippingOverrideToChannels_writesMasterValuesOntoCellWithoutOverride() {
        // Given a cell that has no override of its own — force-apply must still write the master's values in
        // (that is the difference from the old "clear only" semantics: the cell ends up owning the values)
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true)
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_CHARGE_TYPE, "FREE")).build();
        ProductListing cell = ProductListing.builder().id(100L).name("셀A").shippingOverride(null).build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));

        int affected = service.applyShippingOverrideToChannels(1L, null);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<List<ProductListing>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getShippingOverride())
                .containsEntry(ShippingOverrideKeys.DELIVERY_CHARGE_TYPE, "FREE");
    }

    @Test
    void applyShippingOverrideToChannels_masterOverrideEmpty_removesMasterKeysFromCell() {
        // Given a master with NO shipping override: "apply the master" means the cell ends up matching it,
        // so the cell's master-level keys are removed (those fields fall through to the account default).
        // Place keys stay (account-specific, never touched).
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true)
                .shippingOverride(null).build();
        Map<String, String> cellAOverride = new LinkedHashMap<>();
        cellAOverride.put(ShippingOverrideKeys.DELIVERY_METHOD, "MAKE_ORDER");
        cellAOverride.put(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "OUT-1");
        ProductListing cellA = ProductListing.builder().id(100L).name("셀A").shippingOverride(cellAOverride).build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cellA));

        int affected = service.applyShippingOverrideToChannels(1L, null);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<List<ProductListing>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getShippingOverride())
                .doesNotContainKey(ShippingOverrideKeys.DELIVERY_METHOD)
                .containsEntry(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "OUT-1");
    }

    @Test
    void applyShippingOverrideToChannels_selectedListingIds_appliesToThoseOnly() {
        // Given two linked cells but only one selected (79)
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true)
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "SEQUENCIAL")).build();
        ProductListing cellA = ProductListing.builder().id(100L).name("셀A")
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "MAKE_ORDER")).build();
        ProductListing cellB = ProductListing.builder().id(101L).name("셀B")
                .shippingOverride(Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "MAKE_ORDER")).build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cellA, cellB));

        int affected = service.applyShippingOverrideToChannels(1L, List.of(101L));

        // Then only the selected cell is written; the unselected one keeps its own setting
        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<List<ProductListing>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getId()).isEqualTo(101L);
    }

    @Test
    void applyShippingOverrideToChannels_listingIdOutsideMaster_throws400() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        ProductListing cellA = ProductListing.builder().id(100L).name("셀A").build();

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cellA));

        assertThatThrownBy(() -> service.applyShippingOverrideToChannels(1L, List.of(999L)))
                .isInstanceOf(ValidationException.class);
        verify(productListingRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------- market lock + narrow re-sync (84)

    private static final MasterProduct LOCK_MASTER =
            MasterProduct.builder().id(1L).name("마스터A").active(true).build();

    /** A cell that reached the market (platformProductId != null) — the precondition for any lock. */
    private ProductListing onMarketCell(Long id) {
        return ProductListing.builder().id(id).platform("COUPANG").name("셀")
                .platformProductId("SP-" + id).masterProduct(LOCK_MASTER).build();
    }

    private ProductListing draftCell(Long id) {
        return ProductListing.builder().id(id).platform("COUPANG").name("셀")
                .masterProduct(LOCK_MASTER).build();
    }

    private ProductListingOption cellOption(Long id, ProductListing cell, String name,
                                            boolean active, String platformOptionId,
                                            OptionApprovalStatus approval) {
        return ProductListingOption.builder()
                .id(id).productListing(cell).optionName(name)
                .active(active).platformOptionId(platformOptionId).approvalStatus(approval).build();
    }

    private MasterProductOption masterOption(Long id, String name) {
        return MasterProductOption.builder().id(id).masterProduct(LOCK_MASTER).name(name).build();
    }

    /** Wire getMasterProduct(1L) to return one option named "2세트" plus the given channel state. */
    private void givenSingleOptionMaster(List<ProductListing> cells, List<ProductListingOption> cellOptions) {
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(LOCK_MASTER));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption(10L, "2세트")));
        given(productListingRepository.findByMasterProductIdIn(List.of(1L))).willReturn(cells);
        // lenient: skipped entirely when no cell reached the market (the DRAFT-only / no-cell cases)
        lenient().when(productListingOptionRepository.findByProductListingIdIn(any())).thenReturn(cellOptions);
    }

    private Boolean readMarketRegistered() {
        return service.getMasterProduct(1L).getOptions().get(0).getMarketRegistered();
    }

    @Test
    void lockJudgement_onMarketCellWithActiveOption_isLocked() {
        ProductListing cell = onMarketCell(100L);
        givenSingleOptionMaster(List.of(cell),
                List.of(cellOption(5L, cell, "2세트", true, null, OptionApprovalStatus.NOT_APPROVED)));

        assertThat(readMarketRegistered()).isTrue();
    }

    @Test
    void lockJudgement_inactiveButHasPlatformOptionId_isLocked() {
        // Switched off locally, but Coupang issued a vendorItemId → the option still exists there.
        ProductListing cell = onMarketCell(100L);
        givenSingleOptionMaster(List.of(cell),
                List.of(cellOption(5L, cell, "2세트", false, "VI-1", OptionApprovalStatus.NOT_APPROVED)));

        assertThat(readMarketRegistered()).isTrue();
    }

    @Test
    void lockJudgement_inactiveButApproved_isLocked() {
        // Approved options cannot be deleted on Coupang, so an approval record locks it too.
        ProductListing cell = onMarketCell(100L);
        givenSingleOptionMaster(List.of(cell),
                List.of(cellOption(5L, cell, "2세트", false, null, OptionApprovalStatus.APPROVED)));

        assertThat(readMarketRegistered()).isTrue();
    }

    @Test
    void lockJudgement_inactiveWithoutMarketIdOrApproval_isNotLocked() {
        ProductListing cell = onMarketCell(100L);
        givenSingleOptionMaster(List.of(cell),
                List.of(cellOption(5L, cell, "2세트", false, null, OptionApprovalStatus.NOT_APPROVED)));

        assertThat(readMarketRegistered()).isFalse();
    }

    @Test
    void lockJudgement_draftCellOnly_isNotLocked() {
        // A DRAFT cell never reached the market → editable, the mismatch is fixed by the price re-sync.
        ProductListing cell = draftCell(100L);
        givenSingleOptionMaster(List.of(cell),
                List.of(cellOption(5L, cell, "2세트", true, null, OptionApprovalStatus.APPROVED)));

        assertThat(readMarketRegistered()).isFalse();
    }

    @Test
    void lockJudgement_noCells_isNotLocked() {
        givenSingleOptionMaster(List.of(), List.of());

        assertThat(readMarketRegistered()).isFalse();
        verify(productListingOptionRepository, never()).findByProductListingIdIn(any());
    }

    @Test
    void lockJudgement_listPath_queriesLockRepositoriesOnce() {
        // N+1 guard: three masters, still exactly one query per lock repository.
        given(masterProductRepository.findByActiveTrue()).willReturn(List.of(
                MasterProduct.builder().id(1L).name("A").active(true).build(),
                MasterProduct.builder().id(2L).name("B").active(true).build(),
                MasterProduct.builder().id(3L).name("C").active(true).build()));
        ProductListing cell = ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("SP-1")
                .masterProduct(MasterProduct.builder().id(2L).name("B").build()).build();
        given(productListingRepository.findByMasterProductIdIn(List.of(1L, 2L, 3L))).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(
                cellOption(5L, cell, "2세트", true, null, OptionApprovalStatus.NOT_APPROVED)));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(3L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(2L)).willReturn(List.of(
                MasterProductOption.builder().id(10L).name("2세트").build()));

        List<MasterProductResponse> result = service.getMasterProducts();

        assertThat(result.get(1).getOptions().get(0).getMarketRegistered()).isTrue();
        verify(productListingRepository, times(1)).findByMasterProductIdIn(any());
        verify(productListingOptionRepository, times(1)).findByProductListingIdIn(any());
    }

    // --- guards -------------------------------------------------------------

    /** Master 1 has one option (id 10, "2세트") whose vector is {1:2}; locked/unlocked per the cell state. */
    private MasterProductOption givenEditableOption(boolean locked) {
        MasterProductOption option = masterOption(10L, "2세트");
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(LOCK_MASTER));
        given(optionRepository.findById(10L)).willReturn(Optional.of(option));
        // lenient: the delete path never reads the item vector (only updateOption does)
        lenient().when(optionItemRepository.findByOptionId(10L)).thenReturn(List.of(
                MasterProductOptionItem.builder().option(option).product(product(1L, "상품1")).quantity(2).build()));
        ProductListing cell = onMarketCell(100L);
        given(productListingRepository.findByMasterProductIdIn(List.of(1L))).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(
                cellOption(5L, cell, "2세트", locked, null,
                        locked ? OptionApprovalStatus.APPROVED : OptionApprovalStatus.NOT_APPROVED)));
        return option;
    }

    @Test
    void updateOption_lockedOption_renameThrows400() {
        givenEditableOption(true);

        assertThatThrownBy(() -> service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("3세트").items(List.of(item(1L, 2))).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("쿠팡에 등록된 옵션은 이름을 바꿀 수 없습니다.");
        verify(optionRepository, never()).save(any());
    }

    @Test
    void updateOption_lockedOption_quantityChangeThrows400() {
        givenEditableOption(true);

        assertThatThrownBy(() -> service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 3))).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("쿠팡에 등록된 옵션은 수량을 바꿀 수 없습니다.");
        verify(optionItemRepository, never()).deleteByOptionId(any());
    }

    @Test
    void updateOption_lockedOption_sameItemsWithBoxChange_passes() {
        // Regression guard for the oldVector capture: the frontend posts the whole form, so an unchanged
        // item list must go through — otherwise editing only the box would 400.
        givenEditableOption(true);
        given(optionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(packageRepository.findById(5L)).willReturn(Optional.of(Package.builder().id(5L).build()));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(LOCK_MASTER, product(1L, "상품1"))));
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        MasterOptionResponse response = service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 2))).packageId(5L).build());

        assertThat(response.getPackageId()).isEqualTo(5L);
        assertThat(response.getMarketRegistered()).isTrue();
        // quantities did not move → no channel re-sync
        verify(optionQuantitySync, never()).syncLines(any(), any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    @Test
    void deleteOption_lockedOption_throws400() {
        givenEditableOption(true);

        assertThatThrownBy(() -> service.deleteOption(1L, 10L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("쿠팡에 등록된 옵션은 삭제할 수 없습니다. 판매 중지 후 마켓에서 정리하세요.");
        verify(optionRepository, never()).delete(any());
    }

    @Test
    void deleteOption_lastOption_throws400() {
        givenEditableOption(false);
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption(10L, "2세트")));

        assertThatThrownBy(() -> service.deleteOption(1L, 10L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("옵션은 1개 이상 있어야 합니다. 모두 없애려면 마스터를 삭제하세요.");
        verify(optionRepository, never()).delete(any());
    }

    @Test
    void deleteOption_lockedAndLast_reportsTheLockFirst() {
        givenEditableOption(true);

        assertThatThrownBy(() -> service.deleteOption(1L, 10L))
                .hasMessage("쿠팡에 등록된 옵션은 삭제할 수 없습니다. 판매 중지 후 마켓에서 정리하세요.");
    }

    @Test
    void deleteOption_oneOfTwoUnlocked_deletes() {
        givenEditableOption(false);
        given(optionRepository.findByMasterProductId(1L))
                .willReturn(List.of(masterOption(10L, "2세트"), masterOption(11L, "3세트")));

        service.deleteOption(1L, 10L);

        verify(optionItemRepository).deleteByOptionId(10L);
        verify(optionRepository).delete(any());
    }

    // --- name uniqueness ----------------------------------------------------

    @Test
    void createOption_duplicateNameWithinMaster_throws400() {
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(LOCK_MASTER));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption(10L, "2세트")));

        assertThatThrownBy(() -> service.createOption(1L, MasterOptionRequest.builder()
                .name(" 2세트 ").items(List.of(item(1L, 2))).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("같은 이름의 옵션이 이미 있습니다.");
        verify(optionRepository, never()).save(any());
    }

    @Test
    void updateOption_keepingItsOwnName_passes() {
        // Self-exclusion: re-sending the option's current name must not trip the uniqueness check.
        givenEditableOption(false);
        given(optionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(LOCK_MASTER, product(1L, "상품1"))));
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        MasterOptionResponse response = service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 2))).build());

        assertThat(response.getName()).isEqualTo("2세트");
        assertThat(response.getMarketRegistered()).isFalse();
    }

    // --- narrow channel re-sync --------------------------------------------

    /** Master 1, unlocked option "2세트", one DRAFT cell (200) carrying a same-named channel option. */
    private ProductListingOption givenDraftChannel() {
        MasterProductOption option = masterOption(10L, "2세트");
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(LOCK_MASTER));
        given(optionRepository.findById(10L)).willReturn(Optional.of(option));
        given(optionItemRepository.findByOptionId(10L)).willReturn(List.of(
                MasterProductOptionItem.builder().option(option).product(product(1L, "상품1")).quantity(2).build()));
        given(productListingRepository.findByMasterProductIdIn(List.of(1L))).willReturn(List.of());
        given(optionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(LOCK_MASTER, product(1L, "상품1"))));
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));

        ProductListing cell = draftCell(200L);
        ProductListingOption channelOption =
                cellOption(50L, cell, "2세트", true, null, OptionApprovalStatus.NOT_APPROVED);
        // lenient: only read when something downstream actually changed (rename / quantity)
        lenient().when(productListingRepository.findByMasterProductId(1L)).thenReturn(List.of(cell));
        return channelOption;
    }

    @Test
    void updateOption_quantityChange_syncsLinesAndRecalculatesPricesOnly() {
        ProductListingOption channelOption = givenDraftChannel();
        given(productListingOptionRepository.findByProductListingId(200L)).willReturn(List.of(channelOption));

        service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 3))).build());

        verify(optionQuantitySync).syncLines(eq(channelOption), any(MasterProductOption.class));
        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(listingAssetService).recalculateOptionPrices(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(200L);
        // ⚠️ Cost guard (the core of the narrow re-sync): no thumbnail / detail regeneration.
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    @Test
    void updateOption_boxOnlyChange_doesNotResync() {
        givenDraftChannel();
        given(packageRepository.findById(5L)).willReturn(Optional.of(Package.builder().id(5L).build()));

        service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 2))).packageId(5L).build());

        verify(optionQuantitySync, never()).syncLines(any(), any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    @Test
    void updateOption_renameOnly_delegatesCascadeToChannelSync() {
        givenDraftChannel();
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption(10L, "2세트")));

        service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("두세트").items(List.of(item(1L, 2))).build());

        // optionName is the master↔channel match key — leaving it stale would orphan the channel option.
        // 86: the cascade itself lives in MasterOptionChannelSync (asserted in its own test); here we only
        // pin that updateOption hands it the old and new name.
        verify(masterOptionChannelSync).onOptionRenamed(1L, "2세트", "두세트");
        // quantities unchanged → no quantity re-sync
        verify(optionQuantitySync, never()).syncLines(any(), any());
    }

    @Test
    void updateOption_renameAndQuantityChange_cascadesThenResyncs() {
        // Regression guard: after the cascade the channel option carries the NEW name, so the quantity
        // re-sync must match on it — matching the old name would silently find nothing.
        ProductListingOption channelOption = givenDraftChannel();
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(masterOption(10L, "2세트")));
        // The cascade (now MasterOptionChannelSync, mocked here) has already renamed the channel row, so the
        // quantity re-sync that follows reads it under the NEW name.
        ProductListingOption renamed = channelOption.toBuilder().optionName("두세트").build();
        given(productListingOptionRepository.findByProductListingId(200L)).willReturn(List.of(renamed));

        service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("두세트").items(List.of(item(1L, 3))).build());

        verify(masterOptionChannelSync).onOptionRenamed(1L, "2세트", "두세트");
        verify(optionQuantitySync).syncLines(eq(renamed), any(MasterProductOption.class));
        verify(listingAssetService).recalculateOptionPrices(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    // --- 86: option CRUD → channel structure sync --------------------------

    @Test
    void createOption_propagatesTheNewOptionToChannels() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").active(true).build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(componentRepository.findByMasterProductId(1L))
                .willReturn(List.of(component(master, product(1L, "상품1"))));
        given(productRepository.findAllById(any())).willReturn(List.of(product(1L, "상품1")));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(optionRepository.save(any())).willAnswer(inv ->
                ((MasterProductOption) inv.getArgument(0)).toBuilder().id(10L).build());
        given(productListingRepository.findByMasterProductIdIn(List.of(1L))).willReturn(List.of());

        service.createOption(1L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 2))).build());

        ArgumentCaptor<MasterProductOption> captor = ArgumentCaptor.forClass(MasterProductOption.class);
        verify(masterOptionChannelSync).onOptionCreated(eq(1L), captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(10L);      // the persisted option, not the request
        assertThat(captor.getValue().getName()).isEqualTo("2세트");
    }

    @Test
    void deleteOption_switchesTheOptionOffOnChannels() {
        givenEditableOption(false);
        given(optionRepository.findByMasterProductId(1L))
                .willReturn(List.of(masterOption(10L, "2세트"), masterOption(11L, "3세트")));

        service.deleteOption(1L, 10L);

        // Name-keyed, and issued while the master option still exists (its name is the only match key).
        verify(masterOptionChannelSync).onOptionRemoved(1L, "2세트");
    }

    @Test
    void deleteOption_lockedOption_doesNotTouchChannels() {
        // The 84 lock still runs first: a market-registered option 400s before any channel write happens.
        givenEditableOption(true);

        assertThatThrownBy(() -> service.deleteOption(1L, 10L))
                .isInstanceOf(ValidationException.class);
        verify(masterOptionChannelSync, never()).onOptionRemoved(any(), any());
    }

    @Test
    void updateOption_withoutRename_neverCascadesTheName() {
        givenDraftChannel();
        given(productListingOptionRepository.findByProductListingId(200L)).willReturn(List.of());

        service.updateOption(1L, 10L, MasterOptionRequest.builder()
                .name("2세트").items(List.of(item(1L, 3))).build());

        verify(masterOptionChannelSync, never()).onOptionRenamed(any(), any(), any());
    }

    // ------------------------------------------------------------ 89: channel sync preview (read-only)
    //
    // The preview must count ONLY what a propagation run actually removes. Every "not counted" case below is
    // a regression guard: counting it would leave inSync=false for ever (banner never clears, button always lit).

    private ProductListing previewCell(Long id, Seller seller, String platform, String platformProductId) {
        return ProductListing.builder().id(id).seller(seller).platform(platform)
                .platformProductId(platformProductId).name("리스팅" + id).build();
    }

    private ProductListingOption cellOption(Long id, ProductListing cell, String name, boolean active) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName(name)
                .active(active).sellingPrice(new BigDecimal("1000")).build();
    }

    private GeneratedProductData generated(ProductListing cell) {
        return GeneratedProductData.builder().productListing(cell).build();
    }

    private MasterProductOptionItem masterItem(MasterProductOption option, Product product, int quantity) {
        return MasterProductOptionItem.builder().option(option).product(product).quantity(quantity).build();
    }

    private ProductListingProduct cellLine(ProductListingOption option, Product product, int quantity) {
        return ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(quantity).build();
    }

    @Test
    void previewChannelSync_masterOptionAbsentOnCell_isMissing() {
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();
        MasterProductOption m2 = MasterProductOption.builder().id(6L).name("2세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", null);
        ProductListingOption o1 = cellOption(1L, cell, "1세트", true);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1, m2));
        given(optionItemRepository.findByOptionIdIn(any()))
                .willReturn(List.of(masterItem(m1, p1, 1), masterItem(m2, p1, 2)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(o1));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(cellLine(o1, p1, 1)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isFalse();
        assertThat(preview.getChannels()).hasSize(1);
        ChannelSyncPreviewResponse.Channel channel = preview.getChannels().get(0);
        assertThat(channel.getListingId()).isEqualTo(100L);
        assertThat(channel.getSellerName()).isEqualTo("행복상회");
        assertThat(channel.getPlatform()).isEqualTo("COUPANG");
        assertThat(channel.isOnMarket()).isFalse();
        assertThat(channel.getMissingOptions()).containsExactly("2세트");
        assertThat(channel.getOrphanOptions()).isEmpty();
        assertThat(channel.getQuantityMismatchOptions()).isEmpty();
        assertThat(preview.getTotals().getMissingOptions()).isEqualTo(1);
        assertThat(preview.getTotals().getAffectedChannels()).isEqualTo(1);
    }

    @Test
    void previewChannelSync_activeOrphanOnDraftCell_isOrphan() {
        // DRAFT cell (no platformProductId) → syncStructure (2) switches this option off, so it counts.
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", null);
        ProductListingOption kept = cellOption(1L, cell, "1세트", true);
        ProductListingOption orphan = cellOption(2L, cell, "삭제된옵션", true);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 1)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(kept, orphan));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(cellLine(kept, p1, 1)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isFalse();
        assertThat(preview.getChannels().get(0).getOrphanOptions()).containsExactly("삭제된옵션");
        assertThat(preview.getChannels().get(0).getMarketOrphanOptions()).isEmpty();
        assertThat(preview.getTotals().getOrphanOptions()).isEqualTo(1);
        assertThat(preview.getTotals().getAffectedChannels()).isEqualTo(1);
    }

    @Test
    void previewChannelSync_quantityDiffers_sharedProductsOnly_activeAgnostic() {
        // "1세트": the only differing line is cell-only (p3) → syncLines never touches it → NOT a mismatch.
        // "2세트": inactive, but syncOptionQuantities does not read active → the p1 diff DOES count.
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        Product p2 = product(12L, "상품2");
        Product p3 = product(13L, "상품3");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();
        MasterProductOption m2 = MasterProductOption.builder().id(6L).name("2세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", null);
        ProductListingOption o1 = cellOption(1L, cell, "1세트", true);
        ProductListingOption o2 = cellOption(2L, cell, "2세트", false);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1, m2));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(
                masterItem(m1, p1, 1), masterItem(m1, p2, 2),   // p2 is master-only on the cell → skipped
                masterItem(m2, p1, 5)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(o1, o2));
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                cellLine(o1, p1, 1), cellLine(o1, p3, 9),       // p3 is cell-only → left as-is → no mismatch
                cellLine(o2, p1, 3)));                          // 3 != master 5 → mismatch (option is inactive)
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isFalse();
        assertThat(preview.getChannels().get(0).getQuantityMismatchOptions()).containsExactly("2세트");
        assertThat(preview.getChannels().get(0).getMissingOptions()).isEmpty();
        assertThat(preview.getChannels().get(0).getOrphanOptions()).isEmpty();
        assertThat(preview.getTotals().getQuantityMismatch()).isEqualTo(1);
    }

    @Test
    void previewChannelSync_inactiveOrphan_isNotCounted() {
        // Rows are never deleted (decision 42) — an already-off orphan stays off, so propagation writes nothing.
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", null);
        ProductListingOption kept = cellOption(1L, cell, "1세트", true);
        ProductListingOption offOrphan = cellOption(2L, cell, "이미꺼진옵션", false);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 1)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(kept, offOrphan));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(cellLine(kept, p1, 1)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isTrue();
        assertThat(preview.getChannels()).isEmpty();
        assertThat(preview.getTotals().getOrphanOptions()).isZero();
    }

    @Test
    void previewChannelSync_activeOrphanOnMarketCell_isInformationalOnly() {
        // syncStructure leaves an on-market orphan alone (WARN) → reported, but never counted or it would
        // keep inSync=false for ever. The operator stops it in WING.
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", "X");   // on market
        ProductListingOption kept = cellOption(1L, cell, "1세트", true);
        ProductListingOption orphan = cellOption(2L, cell, "삭제된옵션", true);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 1)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(kept, orphan));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(cellLine(kept, p1, 1)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isTrue();
        assertThat(preview.getChannels()).hasSize(1);
        assertThat(preview.getChannels().get(0).isOnMarket()).isTrue();
        assertThat(preview.getChannels().get(0).getMarketOrphanOptions()).containsExactly("삭제된옵션");
        assertThat(preview.getChannels().get(0).getOrphanOptions()).isEmpty();
        assertThat(preview.getTotals().getOrphanOptions()).isZero();
        assertThat(preview.getTotals().getAffectedChannels()).isZero();
    }

    @Test
    void previewChannelSync_cellWithoutGeneratedAssets_isExcludedEntirely() {
        // propagate() counts such a cell as skipped and never touches it → its difference would never clear.
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", null);   // has NO GeneratedProductData

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 1)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any())).willReturn(List.of());

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isTrue();
        assertThat(preview.getChannels()).isEmpty();
        // The cell's options are never even loaded (the skip filter runs first).
        verify(productListingOptionRepository, never()).findByProductListingIdIn(any());
    }

    @Test
    void previewChannelSync_everythingMatches_isInSync() {
        Seller seller = seller(1L, "행복상회");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell = previewCell(100L, seller, "COUPANG", "X");
        ProductListingOption o1 = cellOption(1L, cell, "1세트", true);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 3)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell)));
        given(productListingOptionRepository.findByProductListingIdIn(any())).willReturn(List.of(o1));
        given(productListingProductRepository.findByProductListingOptionIdIn(any()))
                .willReturn(List.of(cellLine(o1, p1, 3)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(seller));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isTrue();
        assertThat(preview.getChannels()).isEmpty();
        assertThat(preview.getTotals().getAffectedChannels()).isZero();
    }

    @Test
    void previewChannelSync_noCells_isInSync() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of());
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of());

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.isInSync()).isTrue();
        assertThat(preview.getChannels()).isEmpty();
    }

    @Test
    void previewChannelSync_missingMaster_throws404() {
        given(masterProductRepository.findScopedById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.previewChannelSync(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void previewChannelSync_threeCellsSixOptions_queriesEachRepositoryOnce() {
        // Query budget: one call per repository regardless of cell/option count. Also pins the channel order
        // (sellerName → platform) that the front end's "first N rows" rendering depends on.
        Seller sellerA = seller(1L, "가판매자");
        Seller sellerB = seller(2L, "나판매자");
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터A").build();
        Product p1 = product(11L, "상품1");
        MasterProductOption m1 = MasterProductOption.builder().id(5L).name("1세트").build();

        ProductListing cell1 = previewCell(100L, sellerB, "COUPANG", null);
        ProductListing cell2 = previewCell(101L, sellerA, "NAVER", null);
        ProductListing cell3 = previewCell(102L, sellerA, "COUPANG", null);
        // Two options per cell: the matched "1세트" + one orphan → every cell has a difference.
        ProductListingOption o11 = cellOption(1L, cell1, "1세트", true);
        ProductListingOption o12 = cellOption(2L, cell1, "고아1", true);
        ProductListingOption o21 = cellOption(3L, cell2, "1세트", true);
        ProductListingOption o22 = cellOption(4L, cell2, "고아2", true);
        ProductListingOption o31 = cellOption(5L, cell3, "1세트", true);
        ProductListingOption o32 = cellOption(6L, cell3, "고아3", true);

        given(masterProductRepository.findScopedById(1L)).willReturn(Optional.of(master));
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(m1));
        given(optionItemRepository.findByOptionIdIn(any())).willReturn(List.of(masterItem(m1, p1, 1)));
        given(productListingRepository.findByMasterProductId(1L)).willReturn(List.of(cell1, cell2, cell3));
        given(generatedProductDataRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(generated(cell1), generated(cell2), generated(cell3)));
        given(productListingOptionRepository.findByProductListingIdIn(any()))
                .willReturn(List.of(o11, o12, o21, o22, o31, o32));
        given(productListingProductRepository.findByProductListingOptionIdIn(any())).willReturn(List.of(
                cellLine(o11, p1, 1), cellLine(o21, p1, 1), cellLine(o31, p1, 1)));
        given(sellerRepository.findAllById(any())).willReturn(List.of(sellerA, sellerB));

        ChannelSyncPreviewResponse preview = service.previewChannelSync(1L);

        assertThat(preview.getTotals().getAffectedChannels()).isEqualTo(3);
        assertThat(preview.getTotals().getOrphanOptions()).isEqualTo(3);
        // 가판매자/COUPANG → 가판매자/NAVER → 나판매자/COUPANG
        assertThat(preview.getChannels()).extracting(ChannelSyncPreviewResponse.Channel::getListingId)
                .containsExactly(102L, 101L, 100L);

        verify(productListingRepository, times(1)).findByMasterProductId(1L);
        verify(generatedProductDataRepository, times(1)).findByProductListingIdIn(any());
        verify(productListingOptionRepository, times(1)).findByProductListingIdIn(any());
        verify(productListingProductRepository, times(1)).findByProductListingOptionIdIn(any());
        verify(optionItemRepository, times(1)).findByOptionIdIn(any());
        verify(sellerRepository, times(1)).findAllById(any());
    }
}
