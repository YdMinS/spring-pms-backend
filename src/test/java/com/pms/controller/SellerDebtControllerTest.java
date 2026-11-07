package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
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
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.stock.LotLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「사업자 간 채무」 (FEATURE_2610_19 D51 · D90 · D99 ① · D137 · D141 · D143 · D145 · D146) on the real schema, plus
 * ADMIN authority (401/403). FEATURE_2611_17 D22: a price filled from the registered price is still typed here.
 */
class SellerDebtControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/seller-debts";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private SellerDebtRepository sellerDebtRepository;
    @Autowired private LotLedgerService lotLedgerService;

    private Seller sellerA;
    private Seller sellerB;

    @BeforeEach
    void seed() {
        sellerA = sellerRepository.save(Seller.builder().sellerName("A상사").businessRegistration("111-11-11111").build());
        sellerB = sellerRepository.save(Seller.builder().sellerName("B상사").businessRegistration("222-22-22222").build());
    }

    private String settleBody(Long payer, Long receiver, String amount) {
        return "{\"payerSellerId\":" + payer + ",\"receiverSellerId\":" + receiver + ",\"settledOn\":\"" + DAY
                + "\",\"amount\":" + amount + ",\"memo\":\"계좌 이체\"}";
    }

    @Test
    void pairs_권한_401_403() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    }

    /** D90 · D99 ①: a settlement moves the balance, 0 won is rejected, a cancelled one is left out. */
    @Test
    void testSettleAndCancel() throws Exception {
        mockMvc.perform(post(PATH + "/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(sellerA.getId(), sellerB.getId(), "0"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(PATH + "/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(sellerA.getId(), sellerB.getId(), "5000"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].debtorId").value(sellerB.getId()))
                .andExpect(jsonPath("$.data[0].balance").value(5000));

        String entries = mockMvc.perform(get(PATH + "/entries").param("sellerA", String.valueOf(sellerA.getId()))
                        .param("sellerB", String.valueOf(sellerB.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("SETTLEMENT"))
                .andReturn().getResponse().getContentAsString();
        long settlementId = objectMapper.readTree(entries).get("data").get(0).get("id").asLong();

        mockMvc.perform(post(PATH + "/settlements/" + settlementId + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** D137 · D141 · D143 · D145 · D146: a 「미기입」 debt is filled and fixed; the empty registered price follows. */
    @Test
    void testFillAndFixUnitPrice() throws Exception {
        Product product = productRepository.save(Product.builder().productName("빈값양말").build());
        StockMovement lot = stockMovementRepository.save(StockMovement.builder()
                .product(product).seller(sellerB).movementType(StockMovementType.STOCK_IN).quantity(2)
                .location(StockLocation.OWN).reason(StockReason.OPENING).movedOn(DAY).createdBy("admin@test.com")
                .build());
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(sellerA).build());
        Order order = orderRepository.save(Order.builder().marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-9").orderedAt(LocalDateTime.of(2026, 11, 2, 10, 0)).build());
        OrderLine line = orderLineRepository.save(OrderLine.builder().order(order).status(OrderStatus.PAID)
                .itemName("빈값양말").orderQty(1).cancelQty(0).holdQty(0).build());
        stockMovementRepository.save(StockMovement.builder()
                .product(product).seller(sellerB).movementType(StockMovementType.STOCK_OUT).quantity(-1)
                .location(StockLocation.OWN).sourceMovement(lot).orderLine(line).movedOn(DAY)
                .createdBy("admin@test.com").build());
        stockMovementRepository.flush();
        lotLedgerService.rebuildDebts(product.getId());
        SellerDebt debt = sellerDebtRepository.findAll().get(0);

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].unknownCount").value(1))
                .andExpect(jsonPath("$.data[0].debtorId").doesNotExist());

        mockMvc.perform(put(PATH + "/" + debt.getId() + "/unit-price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":1200}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("1200");

        Long newDebtId = sellerDebtRepository.findAll().get(0).getId();
        mockMvc.perform(get(PATH + "/entries").param("sellerA", String.valueOf(sellerA.getId()))
                        .param("sellerB", String.valueOf(sellerB.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].amount").value(1200))
                .andExpect(jsonPath("$.data[0].canEditPrice").value(true))
                .andExpect(jsonPath("$.data[0].canFillPrice").value(false));

        mockMvc.perform(put(PATH + "/" + newDebtId + "/unit-price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":1300}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("1300");
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data[0].debtorId").value(sellerA.getId()))
                .andExpect(jsonPath("$.data[0].balance").value(1300));
    }

    /**
     * FEATURE_2611_17 D22: the registered price appears (「상품 수정」) → the empty lot takes it with the 「미기입」 mark and
     * the debt follows; [단가 입력] stays open and a typed price drops the mark.
     */
    @Test
    void testRegisteredPriceFillsEmptyLotAndStaysTypeable() throws Exception {
        Product product = productRepository.save(Product.builder().productName("나중가양말").active(true).build());
        StockMovement lot = stockMovementRepository.save(StockMovement.builder()
                .product(product).seller(sellerB).movementType(StockMovementType.STOCK_IN).quantity(2)
                .location(StockLocation.OWN).reason(StockReason.OPENING).movedOn(DAY).createdBy("admin@test.com")
                .build());
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(sellerA).build());
        Order order = orderRepository.save(Order.builder().marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-17").orderedAt(LocalDateTime.of(2026, 11, 2, 10, 0)).build());
        OrderLine line = orderLineRepository.save(OrderLine.builder().order(order).status(OrderStatus.PAID)
                .itemName("나중가양말").orderQty(1).cancelQty(0).holdQty(0).build());
        stockMovementRepository.save(StockMovement.builder()
                .product(product).seller(sellerB).movementType(StockMovementType.STOCK_OUT).quantity(-1)
                .location(StockLocation.OWN).sourceMovement(lot).orderLine(line).movedOn(DAY)
                .createdBy("admin@test.com").build());
        stockMovementRepository.flush();
        lotLedgerService.rebuildDebts(product.getId());

        mockMvc.perform(patch("/api/products/" + product.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1000}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findById(lot.getId()).orElseThrow()).satisfies(m -> {
            assertThat(m.getUnitPrice()).isEqualByComparingTo("1000");
            assertThat(m.isPriceFromBase()).isTrue();
        });
        SellerDebt filled = sellerDebtRepository.findAll().get(0);
        assertThat(filled.getAmount()).isEqualByComparingTo("1000");
        mockMvc.perform(get(PATH + "/entries").param("sellerA", String.valueOf(sellerA.getId()))
                        .param("sellerB", String.valueOf(sellerB.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].canFillPrice").value(true));

        mockMvc.perform(put(PATH + "/" + filled.getId() + "/unit-price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":1200}").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findById(lot.getId()).orElseThrow().isPriceFromBase()).isFalse();
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("1000");
        mockMvc.perform(get(PATH + "/entries").param("sellerA", String.valueOf(sellerA.getId()))
                        .param("sellerB", String.valueOf(sellerB.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].amount").value(1200))
                .andExpect(jsonPath("$.data[0].canFillPrice").value(false));
    }
}
