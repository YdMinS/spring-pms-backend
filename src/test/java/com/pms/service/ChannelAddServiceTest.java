package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.exception.DuplicateChannelException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Channel add (FEATURE_2608_06 / 15): master → listing copy of <em>all</em> master options (DRAFT, no market
 * id, options + BOM), duplicate-channel guard (409), master-category pre-validation (400), and the
 * empty-master guard (400). The reused {@link ListingAssetService#regenerateAssets} seam is mocked (its
 * internals are covered by {@code ListingAssetServiceTest}); here we only verify it runs once on the new cell.
 */
@ExtendWith(MockitoExtension.class)
class ChannelAddServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private ListingAssetService listingAssetService;
    @InjectMocks private ChannelAddServiceImpl service;

    private static final Long MASTER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long CATEGORY_ID = 3L;

    /** Master with a standard category set (the happy-path shape after 44). */
    private MasterProduct master() {
        return MasterProduct.builder().id(MASTER_ID).name("마스터").active(true)
                .category(Category.builder().id(CATEGORY_ID).name("신발").build()).build();
    }

    private MasterProductOption masterOption(Long id, String name) {
        return MasterProductOption.builder().id(id).name(name).masterProduct(master()).build();
    }

    private Product product(Long id) {
        return Product.builder().id(id).productName("상품" + id).build();
    }

    private MasterProductOptionItem item(MasterProductOption option, Product product, int qty) {
        return MasterProductOptionItem.builder().option(option).product(product).quantity(qty).build();
    }

    private ChannelAddRequest request() {
        return ChannelAddRequest.builder().sellerId(SELLER_ID).platform("COUPANG").build();
    }

    @Test
    void addChannel_happy_copiesAllMasterOptionsToDraftListing_andRegeneratesOnce() {
        MasterProductOption opt1 = masterOption(10L, "1세트");
        MasterProductOption opt2 = masterOption(20L, "2세트");
        Product prodA = product(100L);
        Product prodB = product(200L);

        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, "COUPANG"))
                .willReturn(false);
        // Master owns two options — channel-add copies BOTH (no subset selection).
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(opt1, opt2));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        // Standard category set + a COUPANG mapping present → channel-add passes pre-validation (44).
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(CATEGORY_ID, "COUPANG")).willReturn(true);
        given(masterProductOptionItemRepository.findByOptionIdIn(List.of(10L, 20L)))
                .willReturn(List.of(item(opt1, prodA, 2), item(opt2, prodB, 1)));

        // save returns the entity with an id so the cell/option are addressable downstream.
        given(productListingRepository.save(any())).willAnswer(inv ->
                ((ProductListing) inv.getArgument(0)).toBuilder().id(50L).build());
        given(productListingOptionRepository.save(any())).willAnswer(inv ->
                ((ProductListingOption) inv.getArgument(0)).toBuilder().id(60L).build());

        service.addChannel(MASTER_ID, request());

        // Listing: one saved, DRAFT, no market id, master FK.
        ArgumentCaptor<ProductListing> listingCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(listingCaptor.capture());
        ProductListing saved = listingCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(saved.getPlatformProductId()).isNull();
        assertThat(saved.getMasterProduct().getId()).isEqualTo(MASTER_ID);
        // Category/delivery/box are derived from the master now — the cell's own columns stay null (deprecated).
        assertThat(saved.getCategory()).isNull();
        assertThat(saved.getDelivery()).isNull();
        assertThat(saved.getPackage_()).isNull();

        // Options: BOTH copied — names carried over, platformOptionId null (issued by 3c).
        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(optionCaptor.capture());
        assertThat(optionCaptor.getAllValues()).extracting(ProductListingOption::getOptionName)
                .containsExactly("1세트", "2세트");
        assertThat(optionCaptor.getAllValues()).allSatisfy(o -> assertThat(o.getPlatformOptionId()).isNull());

        // BOM: one row per master item, quantities preserved.
        ArgumentCaptor<ProductListingProduct> bomCaptor = ArgumentCaptor.forClass(ProductListingProduct.class);
        verify(productListingProductRepository, times(2)).save(bomCaptor.capture());
        assertThat(bomCaptor.getAllValues()).extracting(ProductListingProduct::getQuantity)
                .containsExactly(2, 1);

        // Reused seam ran exactly once on the new cell.
        verify(listingAssetService, times(1)).regenerateAssets(any(ProductListing.class));
    }

    @Test
    void addChannel_duplicateChannel_throwsConflict_andNoSave() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, "COUPANG"))
                .willReturn(true);

        assertThatThrownBy(() -> service.addChannel(MASTER_ID, request()))
                .isInstanceOf(DuplicateChannelException.class);

        verify(productListingRepository, never()).save(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    @Test
    void addChannel_masterCategoryUnset_throwsBadRequest_beforeCellSave() {
        // Master has no standard category (44) → 400 before the cell is created.
        MasterProduct noCategory = MasterProduct.builder().id(MASTER_ID).name("마스터").active(true).build();
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(noCategory));
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, "COUPANG"))
                .willReturn(false);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(masterOption(10L, "1세트")));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));

        assertThatThrownBy(() -> service.addChannel(MASTER_ID, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("표준 카테고리 미설정");

        verify(productListingRepository, never()).save(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    @Test
    void addChannel_noCategoryMappingForPlatform_throwsBadRequest_beforeCellSave() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, "COUPANG"))
                .willReturn(false);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(masterOption(10L, "1세트")));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        // Standard category set but no COUPANG mapping → 400, before the cell is created.
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(CATEGORY_ID, "COUPANG")).willReturn(false);

        assertThatThrownBy(() -> service.addChannel(MASTER_ID, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COUPANG 카테고리 매핑 미설정");

        verify(productListingRepository, never()).save(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }

    @Test
    void addChannel_masterWithNoOptions_throwsBadRequest_andNoSave() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(MASTER_ID, SELLER_ID, "COUPANG"))
                .willReturn(false);
        // No options on the master → empty listing would result → 400.
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());

        assertThatThrownBy(() -> service.addChannel(MASTER_ID, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("옵션 없는 마스터");

        verify(productListingRepository, never()).save(any());
        verify(listingAssetService, never()).regenerateAssets(any());
    }
}
