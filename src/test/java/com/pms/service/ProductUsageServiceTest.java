package com.pms.service;

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
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProductUsageService} (FEATURE_2609_69 / A).
 *
 * <p>Mockito only — the usage read is pure composition over repositories, so a Spring context would
 * prove nothing extra (backend rule: services are mocked, controllers are integration).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductUsageService - Unit Tests")
class ProductUsageServiceTest {

    private static final Long PRODUCT_ID = 7L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private MasterProductComponentRepository masterProductComponentRepository;
    @Mock
    private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock
    private ProductListingOptionRepository productListingOptionRepository;
    @Mock
    private ProductListingRepository productListingRepository;
    @Mock
    private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private PurchaseRecordRepository purchaseRecordRepository;
    @Mock
    private ShipmentParcelItemRepository shipmentParcelItemRepository;
    @Mock
    private ProductImageRepository productImageRepository;
    @Mock
    private ShoppingListItemRepository shoppingListItemRepository;
    @Mock
    private PriceChangeLogRepository priceChangeLogRepository;

    @InjectMocks
    private ProductUsageService service;

    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder().id(PRODUCT_ID).productName("생수 2L").active(true).build();
    }

    private void givenProductExists() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
    }

    private MasterProduct master(Long id, String name) {
        return MasterProduct.builder().id(id).name(name).active(true).build();
    }

    private MasterProductComponent component(MasterProduct master) {
        return MasterProductComponent.builder().masterProduct(master).product(product).build();
    }

    private MasterProductOptionItem optionItem(MasterProduct master, Long optionId, String optionName, int qty) {
        MasterProductOption option = MasterProductOption.builder()
                .id(optionId).name(optionName).masterProduct(master).build();
        return MasterProductOptionItem.builder().option(option).product(product).quantity(qty).build();
    }

    /**
     * 2609_71: 셀 옵션은 마스터 옵션을 FK 로 물고, 수량은 <b>마스터 옵션이 정한다</b> — 셀마다 다른 수량이라는
     * 개념이 없어졌다. 그래서 역방향 조회도 (마스터 옵션 item → 연결된 셀 옵션) 순서로 흐른다.
     */
    private ProductListingOption cellOption(Long optionId, String optionName, MasterProductOption master) {
        Seller seller = Seller.builder().id(3L).sellerName("판매자").businessRegistration("123").build();
        ProductListing listing = ProductListing.builder()
                .id(11L).name("셀").platform(Platform.COUPANG).seller(seller)
                .status(ListingStatus.SELLING).build();
        return ProductListingOption.builder()
                .id(optionId).optionName(optionName).productListing(listing)
                .masterProductOption(master).build();
    }

    @Test
    @DisplayName("No links and no history - deletable with empty blockers")
    void testGetUsageWithNoLinks() {
        givenProductExists();
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of());

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        assertThat(res.productId()).isEqualTo(PRODUCT_ID);
        assertThat(res.masterProducts()).isEmpty();
        assertThat(res.listingOptions()).isEmpty();
        assertThat(res.deletable()).isTrue();
        assertThat(res.blockers()).isEmpty();
        assertThat(res.history().stockMovements()).isZero();
    }

    @Test
    @DisplayName("Two master components - two master refs, not deletable, blocker names the count")
    void testGetUsageWithMasterProductLink() {
        givenProductExists();
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID))
                .willReturn(List.of(component(master(1L, "마스터 A")), component(master(2L, "마스터 B"))));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of());

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        assertThat(res.masterProducts()).hasSize(2);
        assertThat(res.masterProducts().get(0).name()).isEqualTo("마스터 A");
        assertThat(res.deletable()).isFalse();
        assertThat(res.blockers()).anyMatch(b -> b.contains("마스터 상품 2개"));
        verify(masterProductComponentRepository, times(1)).findByProductId(PRODUCT_ID);
    }

    @Test
    @DisplayName("Option quantities ride under their master and never change deletable")
    void testGetUsageOptionQuantitiesUnderMaster() {
        givenProductExists();
        MasterProduct master = master(1L, "마스터 A");
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID))
                .willReturn(List.of(component(master)));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(
                        optionItem(master, 30L, "3세트", 3),
                        optionItem(master, 10L, "1세트", 1),
                        optionItem(master, 20L, "2세트", 2)));

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        assertThat(res.masterProducts()).hasSize(1);
        assertThat(res.masterProducts().get(0).optionQuantities()).hasSize(3);
        assertThat(res.masterProducts().get(0).optionQuantities().get(0).optionId()).isEqualTo(10L);
        assertThat(res.masterProducts().get(0).optionQuantities().get(0).quantity()).isEqualTo(1);
        // Only one blocker: the option vector is not an independent mapping (PLAN D2).
        assertThat(res.blockers()).hasSize(1);
    }

    @Test
    @DisplayName("Channels come from the master link, not from the option FK")
    void testGetUsageListsEveryChannelOfTheMaster() {
        givenProductExists();
        MasterProduct master = master(1L, "마스터 A");
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID))
                .willReturn(List.of(component(master)));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(optionItem(master, 61L, "6개입", 6)));
        // 옵션 FK 로 내려오는 셀은 하나뿐 — 나머지 채널은 이 경로로 영영 안 나온다.
        given(productListingOptionRepository.findByMasterProductOption_IdIn(any()))
                .willReturn(List.of());
        Seller seller = Seller.builder().id(3L).sellerName("판매자").businessRegistration("123").build();
        given(productListingRepository.findByMasterProductIdIn(any()))
                .willReturn(List.of(
                        listing(11L, "쿠팡 셀", master, seller),
                        listing(12L, "두 번째 셀", master, seller)));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(3L, Platform.COUPANG))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(99L).accountAlias("쿠팡 본계정").platform(Platform.COUPANG).build()));

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        assertThat(res.masterProducts()).hasSize(1);
        assertThat(res.masterProducts().get(0).channels()).hasSize(2);
        assertThat(res.masterProducts().get(0).channels().get(0).listingName()).isEqualTo("쿠팡 셀");
        assertThat(res.masterProducts().get(0).channels().get(1).listingId()).isEqualTo(12L);
        assertThat(res.masterProducts().get(0).channels()).allSatisfy(c -> {
            assertThat(c.accountAlias()).isEqualTo("쿠팡 본계정");
            assertThat(c.platform()).isEqualTo("COUPANG");
            assertThat(c.status()).isEqualTo("SELLING");
        });
        // 채널이 있어도 삭제 판정은 옵션 FK 기준 그대로다 — 여기서는 마스터 구성품 때문에 막힌다.
        assertThat(res.listingOptions()).isEmpty();
        assertThat(res.deletable()).isFalse();
        // 같은 (판매자, 플랫폼) 채널 둘이라 계정 조회는 한 번만.
        verify(marketplaceAccountRepository, times(1)).findBySeller_IdAndPlatform(3L, Platform.COUPANG);
    }

    private ProductListing listing(Long id, String name, MasterProduct master, Seller seller) {
        return ProductListing.builder()
                .id(id).name(name).platform(Platform.COUPANG).seller(seller)
                .masterProduct(master).status(ListingStatus.SELLING).build();
    }

    @Test
    @DisplayName("Three cell options behind the master options - option refs with quantity and channel alias")
    void testGetUsageWithListingOptionLink() {
        givenProductExists();
        // 옵션 item 만 있고 구성상품 행이 없는 상태 = 불변 위반 → 마스터는 목록에 들어가지 않는다(경고만).
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        MasterProductOption mo1 = MasterProductOption.builder().id(61L).name("옵션 1").masterProduct(master).build();
        MasterProductOption mo2 = MasterProductOption.builder().id(62L).name("옵션 2").masterProduct(master).build();
        MasterProductOption mo3 = MasterProductOption.builder().id(63L).name("옵션 3").masterProduct(master).build();
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(
                        MasterProductOptionItem.builder().option(mo1).product(product).quantity(1).build(),
                        MasterProductOptionItem.builder().option(mo2).product(product).quantity(2).build(),
                        MasterProductOptionItem.builder().option(mo3).product(product).quantity(3).build()));
        given(productListingOptionRepository.findByMasterProductOption_IdIn(any()))
                .willReturn(List.of(
                        cellOption(51L, "옵션 1", mo1),
                        cellOption(52L, "옵션 2", mo2),
                        cellOption(53L, "옵션 3", mo3)));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(3L, Platform.COUPANG))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(99L).accountAlias("쿠팡 본계정").platform(Platform.COUPANG).build()));

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        // 🔴 마스터에 붙은 셀은 옵션 FK 와 무관하게 전부 채널로 나온다(2026-09-23).
        assertThat(res.masterProducts()).isEmpty();
        assertThat(res.listingOptions()).hasSize(3);
        assertThat(res.listingOptions().get(0).name()).isEqualTo("옵션 1");
        assertThat(res.listingOptions().get(2).quantity()).isEqualTo(3);
        assertThat(res.listingOptions()).allSatisfy(o -> {
            // 옵션 id 로는 화면을 열 수 없다 — 셀 id 를 같이 실어야 판매 상품 상세로 갈 수 있다(2026-09-23).
            assertThat(o.listingId()).isEqualTo(11L);
            assertThat(o.listingName()).isEqualTo("셀");
            // 화면이 판매 채널을 마스터 아래로 접으려면 어느 마스터의 것인지 알아야 한다(2026-09-23).
            assertThat(o.masterProductId()).isEqualTo(1L);
            assertThat(o.marketplaceAccountId()).isEqualTo(99L);
            assertThat(o.accountAlias()).isEqualTo("쿠팡 본계정");
            assertThat(o.platform()).isEqualTo("COUPANG");
            assertThat(o.status()).isEqualTo("SELLING");
        });
        assertThat(res.deletable()).isFalse();
        assertThat(res.blockers()).anyMatch(b -> b.contains("판매 옵션 3개"));
        // The channel of three options of the same cell is resolved once, not per line.
        verify(marketplaceAccountRepository, times(1)).findBySeller_IdAndPlatform(3L, Platform.COUPANG);
    }

    @Test
    @DisplayName("History counts are reported but never block deletion")
    void testGetUsageCountsHistory() {
        givenProductExists();
        given(masterProductComponentRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of());
        given(stockMovementRepository.countByProductId(PRODUCT_ID)).willReturn(12L);
        given(purchaseRecordRepository.countByProductId(PRODUCT_ID)).willReturn(3L);
        given(shipmentParcelItemRepository.countByProductId(PRODUCT_ID)).willReturn(8L);

        ProductUsageResponse res = service.getUsage(PRODUCT_ID);

        assertThat(res.history().stockMovements()).isEqualTo(12L);
        assertThat(res.history().purchaseRecords()).isEqualTo(3L);
        assertThat(res.history().shipmentItems()).isEqualTo(8L);
        assertThat(res.deletable()).isTrue();
        assertThat(res.blockers()).isEmpty();
    }

    @Test
    @DisplayName("Unknown product id throws ResourceNotFoundException")
    void testGetUsageProductNotFound() {
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUsage(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
