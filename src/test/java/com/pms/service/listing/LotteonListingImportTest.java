package com.pms.service.listing;

import com.pms.domain.Category;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.ListingImportRequest;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterPlatformCategoryRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ListingAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_24 / D45 · D60 · D64: a LotteON product is importable and fills only the master's missing notices. */
@ExtendWith(MockitoExtension.class)
class LotteonListingImportTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MasterProductComponentRepository masterProductComponentRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private MasterPlatformCategoryRepository masterPlatformCategoryRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private MasterOptionChannelSync masterOptionChannelSync;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingMarketSyncService listingMarketSyncService;
    @Mock private ListingChannel channel;
    @InjectMocks private CoupangListingImportServiceImpl service;

    private static final Long MASTER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long PRODUCT_A = 100L;

    @BeforeEach
    void wireSelf() {
        ReflectionTestUtils.setField(service, "self", service);
    }

    private Product product() {
        return Product.builder().id(PRODUCT_A).brand("노브랜드").productName("상품").build();
    }

    private void givenLotteonMarket(MasterProduct master, Map<String, String> notices, String noticeGroup) {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master));
        given(masterProductComponentRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(
                MasterProductComponent.builder().id(1L).product(product()).build()));
        given(categoryMappingRepository.existsByCategoryIdAndPlatform(CATEGORY_ID, Platform.LOTTEON)).willReturn(true);
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.LOTTEON))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(Platform.LOTTEON).isActive(true).build()));
        given(productListingRepository.findByPlatformProductId("LO555")).willReturn(Optional.empty());
        given(resolver.resolve(Platform.LOTTEON)).willReturn(channel);
        given(channel.fetchProduct(eq("LO555"), any())).willReturn(new ImportedProduct(null, null, "BC1",
                ListingStatus.SUBMITTED, List.of(), noticeGroup, List.of(), List.of(),
                List.of(new ImportedProduct.Option("1L", "LO555_1", null, new BigDecimal("9900"), null, 5,
                        Map.of(), notices)),
                "승인대기", "롯데 상품", null, null, List.of(), null));
        given(platformCategoryRepository.findByPlatformAndCode(Platform.LOTTEON, "BC1")).willReturn(Optional.empty());
        MasterProductOption existing = MasterProductOption.builder().id(10L).name("1L").masterProduct(master).build();
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(existing));
        given(masterProductOptionItemRepository.findByOptionIdIn(List.of(10L))).willReturn(List.of(
                MasterProductOptionItem.builder().option(existing).product(product()).quantity(1).build()));
        given(productListingRepository.save(any())).willAnswer(inv ->
                ((ProductListing) inv.getArgument(0)).toBuilder().id(50L).build());
        given(productListingOptionRepository.save(any())).willAnswer(inv ->
                ((ProductListingOption) inv.getArgument(0)).toBuilder().id(60L).build());
    }

    private ListingImportRequest request() {
        return ListingImportRequest.builder().sellerId(SELLER_ID).platform("LOTTEON").platformProductId("LO555")
                .options(List.of(ListingImportRequest.OptionSpec.builder().itemName("1L").vendorItemId("LO555_1")
                        .masterOptionName("1L").components(List.of(ListingImportRequest.Component.builder()
                                .productId(PRODUCT_A).quantity(1).build())).build()))
                .build();
    }

    private MasterProduct master(String group, Map<String, String> notices) {
        return MasterProduct.builder().id(MASTER_ID).name("마스터").active(true)
                .category(Category.builder().id(CATEGORY_ID).name("생수").build())
                .categoryNoticeGroup(group).categoryNotices(notices).build();
    }

    @Test
    void import_fillsOnlyTheMastersMissingNotices() {
        givenLotteonMarket(master(null, Map.of("제조국", "한국")),
                Map.of("색상", "검정", "제조국", "중국(OEM)"), "의류");

        service.importListing(MASTER_ID, request());

        ArgumentCaptor<MasterProduct> saved = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(saved.capture());
        assertThat(saved.getValue().getCategoryNoticeGroup()).isEqualTo("의류");
        assertThat(saved.getValue().getCategoryNotices()).containsEntry("색상", "검정").containsEntry("제조국", "한국");
        ArgumentCaptor<ProductListingOption> option = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(option.capture());
        assertThat(option.getValue().getPlatformOptionId()).isEqualTo("LO555_1");
    }

    @Test
    void import_nothingMissing_leavesTheMasterAlone() {
        givenLotteonMarket(master("의류", Map.of("색상", "흰색")), Map.of("색상", "검정"), "의류");

        service.importListing(MASTER_ID, request());

        verify(masterProductRepository, never()).save(any());
    }
}
