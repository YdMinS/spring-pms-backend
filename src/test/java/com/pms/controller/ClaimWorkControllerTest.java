package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「반품 재입고」 · 「교환 발송」 lookups and saves (FEATURE_2611_34 D35 ① ~ ④ · ⑥ · ⑦) on the real schema: ADMIN
 * authority, the stock-link gate (D4), the invoice lookups (D18 · D26 · D42), the remaining-count cap (D21) and the
 * ledger rows the saves leave.
 */
class ClaimWorkControllerTest extends BaseIntegrationTest {

    private static final String BASE = "/api/admin/stock";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private OrderShipmentRepository orderShipmentRepository;
    @Autowired private ShipmentParcelRepository shipmentParcelRepository;
    @Autowired private OrderClaimRepository orderClaimRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;

    private MarketplaceAccount account;
    private Order order;
    private Long orderLineId;
    private Long productId;
    private Long sellerId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(seller).build());

        Product product = productRepository.save(Product.builder().productName("양말A").barcodeId("8800000000002")
                .price(new BigDecimal("1000")).imageUrl("https://bucket/products/socks.png").build());
        productId = product.getId();

        MasterProduct master = masterProductRepository.save(
                MasterProduct.builder().name("양말 마스터").active(true).build());
        MasterProductOption masterOption = masterProductOptionRepository.save(
                MasterProductOption.builder().masterProduct(master).name("2켤레").build());
        // One option = 2 products (R6: required = 2 × claim quantity).
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterOption).product(product).quantity(2).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("2켤레").sellingPrice(new BigDecimal("6000"))
                .platformOptionId("vi-1").masterProductOption(masterOption).build());

        order = orderRepository.save(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-1").orderedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build());
        orderLineId = orderLineRepository.save(OrderLine.builder()
                .order(order).status(OrderStatus.DELIVERED).itemName("양말A 2켤레")
                .orderQty(1).cancelQty(0).holdQty(0)
                .productListingOption(listingOption)
                .build()).getId();
    }

    private void stockLinkOn() {
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
    }

    private OrderClaim claim(ClaimType type, String externalClaimId, ClaimStatus status,
                             String collectInvoiceNo, String reshipInvoiceNo) {
        return orderClaimRepository.save(OrderClaim.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .claimType(type).externalClaimId(externalClaimId)
                .externalOrderId("ORD-1").externalItemId("vi-1")
                .orderLine(orderLineRepository.findById(orderLineId).orElseThrow())
                .orderItemMatchAttempts(0).quantity(1)
                .status(status).platformStatus("RU")
                .collectInvoiceNo(collectInvoiceNo).reshipInvoiceNo(reshipInvoiceNo)
                .receivedAt(RECEIVED_AT).syncedAt(RECEIVED_AT).build());
    }

    private OrderClaim returnClaim() {
        return claim(ClaimType.RETURN, "C-1", ClaimStatus.RECEIVED, "1234-5678-9012", null);
    }

    private String body(Long claimId, int quantity) {
        return "{\"movedOn\":\"" + DAY + "\",\"lines\":[{\"orderClaimId\":" + claimId + ",\"productId\":" + productId
                + ",\"quantity\":" + quantity + "}]}";
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private MockHttpServletRequestBuilder postJson(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    @Test
    void claimWork_권한_401_403() throws Exception {
        List<MockHttpServletRequestBuilder> noToken = requests();
        for (MockHttpServletRequestBuilder request : noToken) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder request : requests()) {
            mockMvc.perform(request.header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
        }
    }

    private List<MockHttpServletRequestBuilder> requests() {
        return List.of(
                get(BASE + "/return-work/by-return-invoice").param("value", "1"),
                get(BASE + "/return-work/by-parcel-invoice").param("value", "1"),
                get(BASE + "/return-work/shipped-orders"),
                get(BASE + "/return-work/orders/1"),
                postJson(BASE + "/return-work", body(1L, 1)),
                get(BASE + "/exchange-work/by-reship-invoice").param("value", "1"),
                get(BASE + "/exchange-work/open"),
                postJson(BASE + "/exchange-work", body(1L, 1)));
    }

    /** D4: the return check-in save needs the stock link. */
    @Test
    void testReturnWorkRejectedWhileStockLinkOff() throws Exception {
        OrderClaim claim = returnClaim();

        mockMvc.perform(admin(postJson(BASE + "/return-work", body(claim.getId(), 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("재고 연동이 꺼져 있습니다."));
    }

    /** D18 · D20: lookup by the pickup invoice, check-in, and the remaining count follows the ledger. */
    @Test
    void testReturnInvoiceLookupAndReturnIn() throws Exception {
        OrderClaim claim = returnClaim();
        stockLinkOn();

        mockMvc.perform(admin(get(BASE + "/return-work/by-return-invoice").param("value", "123456789012")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].requiredQty").value(2))
                .andExpect(jsonPath("$.data[0].items[0].remainingQty").value(2))
                .andExpect(jsonPath("$.data[0].items[0].barcode").value("8800000000002"));

        mockMvc.perform(admin(postJson(BASE + "/return-work", body(claim.getId(), 2))))
                .andExpect(status().isOk());

        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findAll())
                .filteredOn(m -> m.getMovementType() == StockMovementType.RETURN_IN)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getQuantity()).isEqualTo(2);
                    assertThat(m.getSeller().getSellerName()).isEqualTo("행복상회");
                    assertThat(m.getSeller().getId()).isEqualTo(sellerId);
                });

        mockMvc.perform(admin(get(BASE + "/return-work/by-return-invoice").param("value", "123456789012")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].remainingQty").value(0));
    }

    /** D21: over the remaining count → 400 named by the product. */
    @Test
    void testReturnInOverRemaining() throws Exception {
        OrderClaim claim = returnClaim();
        stockLinkOn();

        mockMvc.perform(admin(postJson(BASE + "/return-work", body(claim.getId(), 3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("양말A은 더 받을 수 없습니다(남은 2개)"));
    }

    /** D29 · D39: a shipped order with a pending return comes first, and picking it opens its claims. */
    @Test
    void testShippedOrdersThenByOrder() throws Exception {
        order = orderRepository.save(order.toBuilder().orderedAt(LocalDateTime.now().minusDays(1)).build());
        returnClaim();

        mockMvc.perform(admin(get(BASE + "/return-work/shipped-orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].claimPending").value(true));

        mockMvc.perform(admin(get(BASE + "/return-work/orders/" + order.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].requiredQty").value(2));
    }

    /** D26: the box's outbound invoice opens the claims on that bundle's lines. */
    @Test
    void testParcelInvoiceLookup() throws Exception {
        OrderShipment shipment = orderShipmentRepository.save(OrderShipment.builder()
                .order(order).externalShipmentId("B1").build());
        shipmentParcelRepository.save(ShipmentParcel.builder()
                .orderShipment(shipment).invoiceNumber("555500001111").parcelSeq(1)
                .status(ParcelStatus.PACKED).build());
        OrderLine line = orderLineRepository.findById(orderLineId).orElseThrow();
        orderLineRepository.save(line.toBuilder().orderShipment(shipment).build());
        OrderClaim claim = claim(ClaimType.RETURN, "C-1", ClaimStatus.RECEIVED, null, null);

        mockMvc.perform(admin(get(BASE + "/return-work/by-parcel-invoice").param("value", "5555-0000-1111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderClaimId").value(claim.getId()));
    }

    /** D42: an exchange not shipped yet is listed with its remaining count. */
    @Test
    void testOpenExchangesListsPending() throws Exception {
        claim(ClaimType.EXCHANGE, "E-1", ClaimStatus.RECEIVED, null, null);

        mockMvc.perform(admin(get(BASE + "/exchange-work/open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].remainingQty").value(2));
    }

    /** D42 round 5: only DONE leaves the list — a withdrawn exchange stays. */
    @Test
    void testOpenExchangesExcludesDoneKeepsWithdrawn() throws Exception {
        claim(ClaimType.EXCHANGE, "E-1", ClaimStatus.DONE, null, null);
        OrderClaim withdrawn = claim(ClaimType.EXCHANGE, "E-2", ClaimStatus.WITHDRAWN, null, null);

        mockMvc.perform(admin(get(BASE + "/exchange-work/open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderClaimId").value(withdrawn.getId()));
    }

    /** D40 · D42: the exchange shipment is a 「교환 발송」 disposal row tied to the claim. */
    @Test
    void testExchangeShipWritesDisposalWithClaim() throws Exception {
        OrderClaim claim = claim(ClaimType.EXCHANGE, "E-1", ClaimStatus.RECEIVED, null, "5555-6666");
        stockLinkOn();
        String opening = "{\"movedOn\":\"" + DAY + "\",\"lines\":[{\"productId\":" + productId + ",\"quantity\":5"
                + ",\"sellerId\":" + sellerId + ",\"reason\":\"OPENING\"}]}";
        mockMvc.perform(admin(postJson(BASE + "/intake", opening))).andExpect(status().isOk());

        mockMvc.perform(admin(get(BASE + "/exchange-work/by-reship-invoice").param("value", "55556666")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].remainingQty").value(2));

        mockMvc.perform(admin(postJson(BASE + "/exchange-work", body(claim.getId(), 2))))
                .andExpect(status().isOk());

        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findAll())
                .filteredOn(m -> m.getMovementType() == StockMovementType.DISPOSAL)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getQuantity()).isEqualTo(-2);
                    assertThat(m.getReason()).isEqualTo(StockReason.EXCHANGE_SHIP);
                    assertThat(m.getOrderClaim().getId()).isEqualTo(claim.getId());
                });
    }
}
