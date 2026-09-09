package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.domain.StockMovementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 출고 확인 API 통합 테스트 — ADMIN 권한(401/403/200) + 주문→마스터 BOM 전개가 실제 스키마 위에서 도는지.
 *
 * <p>단위 테스트가 못 보는 것을 본다: changeset 079 의 {@code order_line.product_listing_option_id} 매핑,
 * 판매자 유도({@code order → marketplaceAccount → seller}), 그리고 확인 후 원장에 남는 부호.
 */
class StockOutboundControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/stock/outbound";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private StockMovementRepository stockMovementRepository;

    private Long orderLineId;
    private Long productId;
    private Long sellerId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(seller).build());

        Product product = productRepository.save(Product.builder().productName("양말A").build());
        productId = product.getId();

        MasterProduct master = masterProductRepository.save(
                MasterProduct.builder().name("양말 마스터").active(true).build());
        MasterProductOption masterOption = masterProductOptionRepository.save(
                MasterProductOption.builder().masterProduct(master).name("3켤레").build());
        // 마스터 BOM = 전개의 정본(D13): 옵션 1개당 물품 2개.
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterOption).product(product).quantity(2).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("3켤레").sellingPrice(new BigDecimal("6000"))
                .platformOptionId("vi-1").masterProductOption(masterOption).build());

        Order order = orderRepository.save(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-1").orderedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build());
        orderLineId = orderLineRepository.save(OrderLine.builder()
                .order(order).status(OrderStatus.PAID).itemName("양말A 3켤레")
                .orderQty(3).cancelQty(0).holdQty(0)
                .productListingOption(listingOption)      // 중립 링크(changeset 079)
                .build()).getId();
    }

    private String confirmBody(int quantity) {
        return "{\"orderLineId\":" + orderLineId + ",\"movedOn\":\"" + DAY + "\","
                + "\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testOutboundWithUserTokenForbidden() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testOutboundWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void testConfirmWithUserTokenForbidden() throws Exception {
        mockMvc.perform(post(PATH + "/confirm")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testConfirmWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(PATH + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(1)))
                .andExpect(status().isUnauthorized());
    }

    // ---- happy path ----

    @Test
    void testOutboundExpandsThroughMasterBom() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(1))
                .andExpect(jsonPath("$.data.orders[0].orderLineId").value(orderLineId))
                .andExpect(jsonPath("$.data.orders[0].externalOrderId").value("ORD-1"))
                .andExpect(jsonPath("$.data.orders[0].sellerName").value("행복상회"))
                .andExpect(jsonPath("$.data.orders[0].products[0].requiredQty").value(6))   // BOM 2 × 주문 3
                .andExpect(jsonPath("$.data.orders[0].products[0].confirmedQty").value(0))
                .andExpect(jsonPath("$.data.unexpanded").isEmpty());
    }

    @Test
    void testConfirmWritesNegativeLedgerRowAndShrinksRemaining() throws Exception {
        mockMvc.perform(post(PATH + "/confirm")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].movementType").value("STOCK_OUT"))
                .andExpect(jsonPath("$.data[0].quantity").value(-4))
                .andExpect(jsonPath("$.data[0].orderLineId").value(orderLineId))
                .andExpect(jsonPath("$.data[0].createdBy").value(ADMIN_EMAIL));

        assertThat(stockMovementRepository.findAll())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getMovementType()).isEqualTo(StockMovementType.STOCK_OUT);
                    assertThat(m.getSeller().getId()).isEqualTo(sellerId);   // 주문에서 유도
                });

        // 남은 수량은 원장 합계로만 줄어든다(D12): 필요 6 − 확인 4 = 2.
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders[0].products[0].confirmedQty").value(4));
    }

    @Test
    void testConfirmOverRemainingReturnsBadRequest() throws Exception {
        mockMvc.perform(post(PATH + "/confirm")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(7)))
                .andExpect(status().isBadRequest());
    }
}
