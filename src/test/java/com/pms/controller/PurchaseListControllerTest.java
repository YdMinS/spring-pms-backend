package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.domain.ShoppingListItem;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PurchaseListController 통합 테스트 — ADMIN 권한(401/403/200) + 추출/조회/구매기록 happy path.
 * 동일 @PreAuthorize 라 401/403 은 GET / 과 POST /extract 로만 검증(나머지 반복 생략).
 */
class PurchaseListControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private ShoppingListItemRepository shoppingListItemRepository;

    private static final String PATH = "/api/admin/purchase-list";

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("테스트셀러").businessRegistration("123-45-67890").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("쿠팡본점")
                .vendorId("A001").accessKey("ak").secretKey("sk").isActive(true).build());

        Product product = productRepository.save(Product.builder()
                .productName("양말A").name("양말A").build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("P001").name("양말세트").seller(seller).build());
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(new BigDecimal("9900"))
                .platformOptionId("OPT1").build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(2).build());   // BOM: A×2

        orderItemRepository.save(OrderItem.builder()
                .marketplaceAccount(account).platform("COUPANG")
                .externalOrderId("O1").externalBoxId("B1").externalItemId("OPT1")
                .itemName("양말세트").orderCount(3).cancelCount(0).holdCount(0)
                .status("ACCEPT").build());   // 발주가능 3 × BOM 2 = autoQty 6
    }

    @Test
    void getList_권한_401_403_200() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void extract_권한_401_403_200() throws Exception {
        mockMvc.perform(post(PATH + "/extract")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void extract_그리고조회_BOM전개_응답구조검증() throws Exception {
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productName").value("양말A"))
                .andExpect(jsonPath("$.data.items[0].neededQty").value(6))     // 3 × 2
                .andExpect(jsonPath("$.data.items[0].remainingQty").value(6))
                .andExpect(jsonPath("$.data.unmappedOrders").isEmpty());
    }

    @Test
    void addPurchase_201_부분구매후잔여반영() throws Exception {
        // 추출로 라인 생성 → itemId 확보
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        Long itemId = shoppingListItemRepository.findAll().get(0).getId();

        String body = "{\"purchasedOn\":\"" + LocalDate.now() + "\",\"quantity\":4}";
        mockMvc.perform(post(PATH + "/items/" + itemId + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // 잔여 = 6 − 4 = 2 (계속 노출)
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].purchasedQty").value(4))
                .andExpect(jsonPath("$.data.items[0].remainingQty").value(2));
    }
}
