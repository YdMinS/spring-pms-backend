package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product usage endpoint + delete guard (FEATURE_2609_69 / A): authority (401/403/200) and the 409 a linked
 * product gets on DELETE. The seeded product is linked <b>both</b> ways (master component + listing option)
 * so one fixture proves the two branches of the response and the guard at once.
 */
class ProductUsageControllerTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository masterProductComponentRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;

    private Long linkedProductId;

    @BeforeEach
    void seed() {
        Product product = productRepository.save(Product.builder()
                .productName("생수 2L").brand("노브랜드").active(true).build());
        linkedProductId = product.getId();

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("생수 마스터").active(true).build());
        masterProductComponentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product).build());

        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡 본계정").isActive(true).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("6개입").sellingPrice(new BigDecimal("6000")).build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(6).build());
    }

    private String usagePath() {
        return "/api/products/" + linkedProductId + "/usage";
    }

    @Test
    void testGetUsageWithoutToken() throws Exception {
        mockMvc.perform(get(usagePath())).andExpect(status().isUnauthorized());
    }

    @Test
    void testGetUsageWithNonAdminRole() throws Exception {
        mockMvc.perform(get(usagePath()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testGetUsageWithAdminRole() throws Exception {
        mockMvc.perform(get(usagePath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(linkedProductId))
                .andExpect(jsonPath("$.data.masterProducts.length()").value(1))
                .andExpect(jsonPath("$.data.masterProducts[0].name").value("생수 마스터"))
                .andExpect(jsonPath("$.data.listingOptions.length()").value(1))
                .andExpect(jsonPath("$.data.listingOptions[0].name").value("6개입"))
                .andExpect(jsonPath("$.data.listingOptions[0].quantity").value(6))
                .andExpect(jsonPath("$.data.listingOptions[0].accountAlias").value("쿠팡 본계정"))
                .andExpect(jsonPath("$.data.history.stockMovements").value(0))
                .andExpect(jsonPath("$.data.deletable").value(false))
                .andExpect(jsonPath("$.data.blockers.length()").value(2));
    }

    @Test
    void testDeleteProductInUseReturns409() throws Exception {
        mockMvc.perform(delete("/api/products/" + linkedProductId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("마스터 상품 1개")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("삭제할 수 없습니다")));
    }
}
