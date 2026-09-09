package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.domain.ShoppingListItem;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
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
import java.time.LocalDateTime;

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
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderShipmentRepository orderShipmentRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private CoupangOrderLineRepository coupangOrderLineRepository;
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
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡본점")
                .isActive(true).build());

        Product product = productRepository.save(Product.builder()
                .productName("양말A").build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("P001").name("양말세트").seller(seller).build());
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(new BigDecimal("9900"))
                .platformOptionId("OPT1").build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(2).build());   // BOM: A×2

        // 주문 3층 + 쿠팡 거울 (2609_26). 추출 윈도우(syncDays) 안에 들도록 ordered_at 은 지금.
        Order order = orderRepository.save(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O1").orderedAt(LocalDateTime.now()).build());
        OrderShipment shipment = orderShipmentRepository.save(OrderShipment.builder()
                .order(order).externalShipmentId("B1").build());
        OrderLine line = orderLineRepository.save(OrderLine.builder()
                .order(order).orderShipment(shipment)
                .status(OrderStatus.PAID).itemName("양말세트")
                .orderQty(3).cancelQty(0).holdQty(0).build());
        // 옵션 매칭키(vendorItemId)는 거울에 있다 — 여기 값이 platformOptionId 와 맞아야 BOM 이 전개된다.
        coupangOrderLineRepository.save(CoupangOrderLine.builder()
                .orderLine(line).marketplaceAccount(account)
                .shipmentBoxId("B1").orderIdRaw("O1").vendorItemId("OPT1")
                .platformStatus("ACCEPT").build());   // 발주가능 3 × BOM 2 = autoQty 6
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

    @Test
    void addPurchase_권한_401_403() throws Exception {
        String body = "{\"purchasedOn\":\"" + LocalDate.now() + "\",\"quantity\":1}";
        mockMvc.perform(post(PATH + "/items/1/purchases")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/items/1/purchases")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    /** 총액이 정본이고 단가는 파생 — 나눠떨어지지 않아도 총액은 그대로 조회된다(FEATURE_2609_28 / PLAN D1). */
    @Test
    void addPurchase_금액저장_조회응답에총액단가반영플래그노출() throws Exception {
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        Long itemId = shoppingListItemRepository.findAll().get(0).getId();

        String body = "{\"purchasedOn\":\"" + LocalDate.now()
                + "\",\"quantity\":3,\"totalAmount\":10000,\"reflectToBasePrice\":false}";
        mockMvc.perform(post(PATH + "/items/" + itemId + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lines[0].records[0].totalAmount").value(10000.00))
                .andExpect(jsonPath("$.data.items[0].lines[0].records[0].unitPrice").value(3333.3333))
                .andExpect(jsonPath("$.data.items[0].lines[0].records[0].reflectToBasePrice").value(false));
    }

    /** 총액과 단가를 동시에 보내면 무엇이 정본인지 모호해진다 → 400. */
    @Test
    void addPurchase_총액과단가동시입력_400() throws Exception {
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        Long itemId = shoppingListItemRepository.findAll().get(0).getId();

        String body = "{\"purchasedOn\":\"" + LocalDate.now()
                + "\",\"quantity\":3,\"totalAmount\":10000,\"unitPrice\":3333}";
        mockMvc.perform(post(PATH + "/items/" + itemId + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
