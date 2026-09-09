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
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
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
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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
 * PurchaseListController 통합 테스트 — ADMIN 권한(401/403/200) + 추출/조회/입고 happy path.
 * 동일 @PreAuthorize 라 401/403 은 대표 경로로만 검증(나머지 반복 생략).
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
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;

    private static final String PATH = "/api/admin/purchase-list";

    private Long productId;
    private Long sellerId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("테스트셀러").businessRegistration("123-45-67890").build());
        sellerId = seller.getId();
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡본점")
                .isActive(true).build());

        Product product = productRepository.save(Product.builder()
                .productName("양말A").build());
        productId = product.getId();
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

    private String purchaseBody(int quantity, String extra) {
        return "{\"productId\":" + productId + ",\"sellerId\":" + sellerId
                + ",\"purchasedOn\":\"" + LocalDate.now() + "\",\"quantity\":" + quantity + extra + "}";
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
                .andExpect(jsonPath("$.data.items[0].lines[0].sellerName").value("테스트셀러"))
                .andExpect(jsonPath("$.data.items[0].lines[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.unmappedOrders").isEmpty());
    }

    /** 🔴 D1 회귀: [입고] 1회가 두 원장을 쓴다. 하나만 남으면 "돈은 나갔는데 물건은 모르는" 행이 생긴다. */
    @Test
    void testAddPurchaseCreatesStockMovement() throws Exception {
        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody(4, ",\"totalAmount\":12000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockRecorded").value(true));

        // @Transactional 테스트라 DB 단언 전에 flush 가 필요하다.
        stockMovementRepository.flush();
        List<StockMovement> movements = stockMovementRepository.findAll();
        assertThat(movements).singleElement().satisfies(m -> {
            assertThat(m.getMovementType()).isEqualTo(StockMovementType.STOCK_IN);
            assertThat(m.getReason()).isEqualTo(StockReason.PURCHASE);
            assertThat(m.getSeller().getId()).isEqualTo(sellerId);
            assertThat(m.getProduct().getId()).isEqualTo(productId);
            assertThat(m.getPurchaseRecord().getId())
                    .isEqualTo(purchaseRecordRepository.findAll().get(0).getId());
            // 단가는 구매기록에서 승계된다(D16) — 12000 / 4.
            assertThat(m.getUnitPrice()).isEqualByComparingTo("3000.0000");
        });
    }

    @Test
    void testAddPurchaseRequiresSeller() throws Exception {
        String body = "{\"productId\":" + productId + ",\"purchasedOn\":\"" + LocalDate.now()
                + "\",\"quantity\":4}";
        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addPurchase_부분구매후잔여반영() throws Exception {
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody(4, "")))
                .andExpect(status().isOk());

        // 잔여 = 6 − 4 = 2 (계속 노출)
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].purchasedQty").value(4))
                .andExpect(jsonPath("$.data.items[0].remainingQty").value(2));
    }

    @Test
    void addPurchase_권한_401_403() throws Exception {
        mockMvc.perform(post(PATH + "/purchases")
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody(1, "")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody(1, "")))
                .andExpect(status().isForbidden());
    }

    /** 총액과 단가를 동시에 보내면 무엇이 정본인지 모호해진다 → 400. */
    @Test
    void addPurchase_총액과단가동시입력_400() throws Exception {
        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(3, ",\"totalAmount\":10000,\"unitPrice\":3333")))
                .andExpect(status().isBadRequest());
    }

    /** D9: 물품 기준·판매자 무관·최신순. 기본 5건. */
    @Test
    void testRecentPurchases() throws Exception {
        Seller other = sellerRepository.save(Seller.builder()
                .sellerName("다른셀러").businessRegistration("999-99-99999").build());
        Product product = productRepository.findById(productId).orElseThrow();
        for (int i = 0; i < 6; i++) {
            purchaseRecordRepository.save(PurchaseRecord.builder()
                    .product(product).seller(i % 2 == 0 ? sellerOf() : other)
                    .purchasedOn(LocalDate.now().minusDays(i)).quantity(i + 1)
                    .reflectToBasePrice(true).build());
        }
        purchaseRecordRepository.flush();

        mockMvc.perform(get(PATH + "/purchases").param("productId", productId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].quantity").value(1))       // 오늘 = 최신
                .andExpect(jsonPath("$.data[0].sellerName").value("테스트셀러"))
                .andExpect(jsonPath("$.data[1].sellerName").value("다른셀러"));

        mockMvc.perform(get(PATH + "/purchases").param("productId", productId.toString())
                        .param("limit", "2").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void recentPurchases_권한_401_403() throws Exception {
        mockMvc.perform(get(PATH + "/purchases").param("productId", productId.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH + "/purchases").param("productId", productId.toString())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /**
     * 🔴 D20 회귀: purchase-candidates 의 JPQL 이 깨지면 컨텍스트 로딩부터 실패한다.
     * D19 로 입고가 항상 즉시 반영되므로 빈 배열이 정상이다.
     */
    @Test
    void testPurchaseCandidatesStillLoads() throws Exception {
        mockMvc.perform(get("/api/admin/stock/purchase-candidates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /** D21: 완료탭도 물품 단위 그룹 + 주문 줄을 그대로 낸다(응답 타입이 같다). */
    @Test
    void testCompletedListStillGroupsByProduct() throws Exception {
        mockMvc.perform(post(PATH + "/extract").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(PATH + "/purchases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody(6, "")))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH + "/completed")
                        .param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].productName").value("양말A"))
                .andExpect(jsonPath("$.data[0].remainingQty").value(0))
                .andExpect(jsonPath("$.data[0].lines[0].sellerName").value("테스트셀러"));
    }

    private Seller sellerOf() {
        return sellerRepository.findById(sellerId).orElseThrow();
    }
}
