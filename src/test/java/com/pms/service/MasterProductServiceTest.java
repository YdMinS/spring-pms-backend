package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pure coverage-matrix mapping: accounts LEFT JOIN listings by (sellerId|platform), N+1 guard, 404.
 */
@ExtendWith(MockitoExtension.class)
class MasterProductServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
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
}
