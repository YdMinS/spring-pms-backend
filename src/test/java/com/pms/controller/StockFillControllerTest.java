package com.pms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.CostBasis;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.SellerDebt;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import com.pms.service.stock.OrderLineExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEATURE_2611_22: an order outbound row that left without a lot, filled later from a lot (D3 · D5 · D8 · D9 · D10 ·
 * D11 · D15 · D16 · D17 · D18 · D20 · D21 · D22 · D23 · D29 · D32 · D34 · D35 · D36 · D38), on the real schema plus
 * ADMIN authority (401/403). The order lines expand through a mocked {@link OrderLineExpander} (one product, the
 * order quantity).
 */
class StockFillControllerTest extends BaseIntegrationTest {

    private static final String FILL = "/api/admin/stock/fill";
    private static final LocalDate DAY_3 = LocalDate.of(2026, 11, 3);
    private static final LocalDate DAY_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDate DAY_7 = LocalDate.of(2026, 11, 7);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private SellerDebtRepository sellerDebtRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @MockBean private OrderLineExpander orderLineExpander;

    private Seller sellerA;
    private Seller sellerB;
    private Product product;
    private MarketplaceAccount accountA;
    private MarketplaceAccount accountB;

    @BeforeEach
    void seed() {
        sellerA = sellerRepository.save(Seller.builder()
                .sellerName("A상사").businessRegistration("111-11-11111").build());
        sellerB = sellerRepository.save(Seller.builder()
                .sellerName("B상사").businessRegistration("222-22-22222").build());
        product = productRepository.save(Product.builder().productName("채움양말").barcodeId("8800000000222")
                .price(new BigDecimal("1000")).build());
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
        accountA = marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder().seller(sellerA)
                .build());
        accountB = marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder().seller(sellerB)
                .build());
        given(orderLineExpander.expand(anyCollection(), any())).willAnswer(invocation -> {
            Map<Long, OrderLineExpander.LineExpansion> result = new LinkedHashMap<>();
            for (Object item : (Collection<?>) invocation.getArgument(0)) {
                OrderLine line = (OrderLine) item;
                result.put(line.getId(), new OrderLineExpander.LineExpansion(line.getId(), List.of(
                        new OrderLineExpander.ExpandedProduct(product.getId(), "채움양말", line.getOrderQty(), null)),
                        null));
            }
            return result;
        });
    }

    private OrderLine orderLine(MarketplaceAccount account, String orderId, int qty) {
        Order order = orderRepository.save(Order.builder().marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(orderId).orderedAt(DAY_3.atTime(9, 0)).build());
        return orderLineRepository.save(OrderLine.builder().order(order).status(OrderStatus.PAID).itemName("채움양말")
                .orderQty(qty).cancelQty(0).holdQty(0).costBasis(CostBasis.LISTED)
                .costAmount(new BigDecimal("1000").multiply(BigDecimal.valueOf(qty))).build());
    }

    private StockMovement emptyOut(OrderLine line, Seller seller, int qty, LocalDate day) {
        return stockMovementRepository.save(StockMovement.builder().product(product).seller(seller)
                .movementType(StockMovementType.STOCK_OUT).location(StockLocation.OWN).quantity(-qty)
                .orderLine(line).movedOn(day).build());
    }

    private StockMovement lot(StockMovementType type, StockReason reason, Seller seller, int qty, LocalDate day) {
        return stockMovementRepository.save(StockMovement.builder().product(product).seller(seller).movementType(type)
                .reason(reason).location(StockLocation.OWN).quantity(qty).unitPrice(new BigDecimal("1000"))
                .movedOn(day).build());
    }

    private ResultActions fill(String fills) throws Exception {
        return mockMvc.perform(post(FILL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fills\":[" + fills + "]}")
                .header("Authorization", "Bearer " + adminToken));
    }

    private static String fillOf(long lotId, String lines) {
        return "{\"lotId\":" + lotId + ",\"lines\":[" + lines + "]}";
    }

    private static String line(long movementId, int quantity) {
        return "{\"movementId\":" + movementId + ",\"quantity\":" + quantity + "}";
    }

    private JsonNode data(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode daily(LocalDate day) throws Exception {
        return data(mockMvc.perform(get("/api/admin/stock/daily").param("date", day.toString())
                .header("Authorization", "Bearer " + adminToken))).get("rows");
    }

    private ResultActions movement(String type, String reason, long sellerId, int qty, String extra) throws Exception {
        return mockMvc.perform(post("/api/admin/stock/movements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + product.getId() + ",\"sellerId\":" + sellerId + ",\"movementType\":\""
                        + type + "\",\"quantity\":" + qty + ",\"reason\":\"" + reason + "\",\"movedOn\":\"" + DAY_7
                        + "\"" + extra + "}")
                .header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions rowPrice(long movementId, int unitPrice, LocalDate day) throws Exception {
        String json = "{\"movementId\":" + movementId + ",\"unitPrice\":" + unitPrice + "}";
        return mockMvc.perform(multipart("/api/admin/stock/daily/row-edit")
                .file(new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8)))
                .param("movedOn", day.toString()).header("Authorization", "Bearer " + adminToken));
    }

    private OrderLine reload(OrderLine line) {
        return orderLineRepository.findById(line.getId()).orElseThrow();
    }

    @Test
    void fill_권한_401_403() throws Exception {
        String body = "{\"fills\":[]}";
        mockMvc.perform(get(FILL + "/candidates").param("lotIds", "1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILL + "/lots").param("movementId", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(FILL).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D3 ① · D5 · D8 · D9 · D10 · D13 · D17 · D21 · D22 · D23 · D30: an intake save fills, splits, owes and costs. */
    @Test
    void testIntakeSaveOffersRowsOwnFirstThenFillSplitsOwesAndCosts() throws Exception {
        OrderLine lineB = orderLine(accountB, "ORD-2622-B", 2);
        OrderLine lineA = orderLine(accountA, "ORD-2622-A", 3);
        StockMovement outB = emptyOut(lineB, sellerB, 2, DAY_3);
        StockMovement outA = emptyOut(lineA, sellerA, 3, DAY_5);
        JsonNode saved = data(mockMvc.perform(post("/api/admin/purchase-list/purchases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + product.getId() + ",\"sellerId\":" + sellerA.getId()
                        + ",\"purchasedOn\":\"" + DAY_7 + "\",\"quantity\":4,\"unitPrice\":800,\"reflectToBasePrice\":false,\"recordStock\":true}")
                .header("Authorization", "Bearer " + adminToken)));
        long lotId = saved.get("lotId").asLong();

        JsonNode window = data(mockMvc.perform(get(FILL + "/candidates").param("lotIds", lotId + ",999999")
                .header("Authorization", "Bearer " + adminToken)));
        assertThat(window).hasSize(1);
        assertThat(window.get(0).get("rowCount").asInt()).isEqualTo(2);                     // D30: 「2건(5개)」
        assertThat(window.get(0).get("emptyQuantity").asInt()).isEqualTo(5);
        JsonNode rows = window.get(0).get("lots").get(0).get("rows");
        assertThat(rows.get(0).get("movementId").asLong()).isEqualTo(outA.getId());          // D17: own first
        assertThat(rows.get(0).get("checkedQuantity").asInt()).isEqualTo(3);                 // D23
        assertThat(rows.get(1).get("orderNumber").asText()).isEqualTo("ORD-2622-B");
        assertThat(rows.get(1).get("checkedQuantity").asInt()).isEqualTo(1);

        fill(fillOf(lotId, line(outA.getId(), 3) + "," + line(outB.getId(), 1))).andExpect(status().isOk());
        stockMovementRepository.flush();
        StockMovement filledA = stockMovementRepository.findById(outA.getId()).orElseThrow();
        assertThat(filledA.getSourceMovement().getId()).isEqualTo(lotId);
        assertThat(filledA.getFilledBy()).isEqualTo(ADMIN_EMAIL);
        StockMovement filledB = stockMovementRepository.findById(outB.getId()).orElseThrow();
        assertThat(filledB.getQuantity()).isEqualTo(-1);                                     // D8: split
        assertThat(filledB.getSeller().getId()).isEqualTo(sellerA.getId());                  // D124: lot owner
        List<StockMovement> restB = stockMovementRepository
                .findByOrderLineIdAndMovementTypeOrderByIdAsc(lineB.getId(), StockMovementType.STOCK_OUT);
        assertThat(restB).hasSize(2);
        assertThat(restB.get(1).getQuantity()).isEqualTo(-1);
        assertThat(restB.get(1).getSourceMovement()).isNull();
        assertThat(restB.get(1).getMovedOn()).isEqualTo(DAY_3);
        assertThat(sellerDebtRepository.findAll()).singleElement().satisfies((SellerDebt debt) -> {   // D5
            assertThat(debt.getBorrower().getId()).isEqualTo(sellerB.getId());
            assertThat(debt.getQuantity()).isEqualTo(1);
            assertThat(debt.getUnitPrice()).isEqualByComparingTo("800");
        });
        assertThat(reload(lineA).getCostBasis()).isEqualTo(CostBasis.LOT_PRICE);              // D10 · D21
        assertThat(reload(lineA).getCostAmount()).isEqualByComparingTo("2400");
        assertThat(reload(lineB).getCostBasis()).isEqualTo(CostBasis.LISTED);                 // 1 × 800 + 1 × 1000
        assertThat(reload(lineB).getCostAmount()).isEqualByComparingTo("1800");

        JsonNode day5 = daily(DAY_5).get(0);
        assertThat(day5.get("filledLater").asBoolean()).isTrue();                            // D9
        assertThat(day5.get("fillable").asBoolean()).isFalse();
        assertThat(day5.get("sourceMovedOn").asText()).isEqualTo("2026-11-07");
        JsonNode day3 = daily(DAY_3);
        assertThat(day3.get(1).get("fillable").asBoolean()).isTrue();

        rowPrice(lotId, 900, DAY_7).andExpect(status().isOk());                             // D22
        assertThat(reload(lineA).getCostAmount()).isEqualByComparingTo("2700");
    }

    /** D11 · D15 · D16 · D18 · D35 · D27: the lots of a row, the refusals and the stock link switch. */
    @Test
    void testRowFillListsLotsRefusesOverCountsAndFollowsTheStockLink() throws Exception {
        OrderLine lineA = orderLine(accountA, "ORD-2622-C", 3);
        StockMovement out = emptyOut(lineA, sellerA, 3, DAY_3);
        StockMovement adjust = lot(StockMovementType.ADJUST, StockReason.COUNT_DIFF, sellerB, 2, DAY_7);
        StockMovement opening = lot(StockMovementType.STOCK_IN, StockReason.OPENING, sellerA, 5, DAY_5);
        lot(StockMovementType.RETURN_IN, null, sellerA, 4, DAY_3);                            // D18: never offered

        JsonNode lots = data(mockMvc.perform(get(FILL + "/lots").param("movementId", out.getId().toString())
                .header("Authorization", "Bearer " + adminToken)));
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).get("lotId").asLong()).isEqualTo(opening.getId());            // day order
        assertThat(lots.get(1).get("sellerName").asText()).isEqualTo("B상사");
        fill(fillOf(adjust.getId(), line(out.getId(), 3))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 입고 수량(2개)보다 많습니다."));
        fill(fillOf(opening.getId(), line(out.getId(), 4))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("빈 출고 수량(3개)보다 많습니다."));

        TenantPurchaseSetting setting = tenantPurchaseSettingRepository.findFirstByOrderByIdAsc().orElseThrow();
        tenantPurchaseSettingRepository.save(setting.toBuilder().stockLinkedAt(LocalDateTime.now().plusDays(1))
                .build());                                                                    // D11: written before
        mockMvc.perform(get(FILL + "/lots").param("movementId", out.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("채울 수 없는 줄입니다."));
        assertThat(daily(DAY_3).get(0).get("fillable").asBoolean()).isFalse();

        mockMvc.perform(put("/api/admin/purchase-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockLinked\":false}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        fill(fillOf(opening.getId(), line(out.getId(), 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("재고 연동이 꺼져 있습니다."));                  // D35
        assertThat(data(mockMvc.perform(get(FILL + "/candidates").param("lotIds", opening.getId().toString())
                .header("Authorization", "Bearer " + adminToken)))).isEmpty();
        mockMvc.perform(put("/api/admin/purchase-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockLinked\":true}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        LocalDateTime linkedAt = tenantPurchaseSettingRepository.findFirstByOrderByIdAsc().orElseThrow()
                .getStockLinkedAt();
        assertThat(linkedAt).isBefore(LocalDateTime.now().plusMinutes(1));                   // off → on stamps now
        StockMovement later = emptyOut(lineA, sellerA, 1, DAY_7);
        fill(fillOf(opening.getId(), line(later.getId(), 1))).andExpect(status().isOk());
        fill(fillOf(opening.getId(), line(out.getId(), 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("채울 수 없는 줄입니다."));
    }

    /** D34 · D35 · D36: a disposal stops at the smaller of the on-hand balance and the seller's decided lots. */
    @Test
    void testDisposalStopsAtTheSmallerOfBalanceAndDecidedLots() throws Exception {
        OrderLine lineA = orderLine(accountA, "ORD-2622-D", 3);
        emptyOut(lineA, sellerA, 3, DAY_3);
        lot(StockMovementType.STOCK_IN, StockReason.OPENING, sellerA, 5, DAY_5);
        movement("DISPOSAL", "DAMAGED", sellerA.getId(), 3, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 재고 수량(2개)보다 많습니다."));
        movement("ADJUST", "COUNT_DIFF", sellerA.getId(), -3, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 재고 수량(2개)보다 많습니다."));
        movement("DISPOSAL", "DAMAGED", sellerA.getId(), 2, "").andExpect(status().isOk());
        movement("DISPOSAL", "DAMAGED", sellerB.getId(), 1, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 재고 수량(0개)보다 많습니다."));

        TenantPurchaseSetting setting = tenantPurchaseSettingRepository.findFirstByOrderByIdAsc().orElseThrow();
        tenantPurchaseSettingRepository.save(setting.toBuilder().stockLinked(false).build());
        movement("DISPOSAL", "DAMAGED", sellerA.getId(), 10, "").andExpect(status().isOk());   // D35
    }

    /** D29 · D38 · D22: an 「기타」 intake filled from the registered price fills an order, then takes a typed price. */
    @Test
    void testEtcIntakeFillsAtItsPriceAndTakesATypedPrice() throws Exception {
        OrderLine lineA = orderLine(accountA, "ORD-2622-E", 2);
        StockMovement out = emptyOut(lineA, sellerA, 2, DAY_3);
        JsonNode etc = data(movement("STOCK_IN", "ETC", sellerA.getId(), 2, ",\"reasonNote\":\"선물\""));
        long lotId = etc.get("id").asLong();
        JsonNode etcRow = daily(DAY_7).get(0);
        assertThat(etcRow.get("openingPriceEditable").asBoolean()).isTrue();                 // D38
        assertThat(etcRow.get("priceFromBase").asBoolean()).isTrue();

        fill(fillOf(lotId, line(out.getId(), 2))).andExpect(status().isOk());
        assertThat(reload(lineA).getCostBasis()).isEqualTo(CostBasis.LOT_PRICE);              // D29: the filled price
        assertThat(reload(lineA).getCostAmount()).isEqualByComparingTo("2000");
        rowPrice(lotId, 700, DAY_7).andExpect(status().isOk());
        assertThat(reload(lineA).getCostAmount()).isEqualByComparingTo("1400");
    }
}
