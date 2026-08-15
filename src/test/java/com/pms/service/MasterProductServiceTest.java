package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private SellerRepository sellerRepository;
    @InjectMocks private MasterProductServiceImpl service;

    private Seller seller(Long id, String name) {
        return Seller.builder().id(id).sellerName(name).businessRegistration(id + "-x").build();
    }

    private MarketplaceAccount account(Long id, Seller seller, String platform, String alias) {
        return MarketplaceAccount.builder()
                .id(id).seller(seller).platform(platform).accountAlias(alias).build();
    }

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).name(name).build();
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
    void getMatrix_missingMaster_throws404() {
        given(masterProductRepository.findScopedById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMatrix(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------- master create

    @Test
    void createMasterProduct_happy_savesComponents() {
        MasterProductRequest request = MasterProductRequest.builder()
                .name("마스터A").componentProductIds(List.of(1L, 2L)).build();
        given(productRepository.findAllById(any()))
                .willReturn(List.of(product(1L, "상품1"), product(2L, "상품2")));
        given(masterProductRepository.save(any()))
                .willReturn(MasterProduct.builder().id(5L).name("마스터A").active(true).build());
        // mapToResponse re-reads the (empty) component/option sets of the saved master
        given(componentRepository.findByMasterProductId(5L)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(5L)).willReturn(List.of());

        MasterProductResponse response = service.createMasterProduct(request);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getActive()).isTrue();
        // one component row saved per requested product
        verify(componentRepository, times(2)).save(any());
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
}
