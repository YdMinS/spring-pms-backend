package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StockController 통합 테스트 — ADMIN 권한(401/403/200) + 잔량/이력 조회.
 *
 * 경로는 {@code /api/admin/stock} 이다 — 레거시 {@code /api/stock} 스택은 제거됐고(PLAN 2609_28 D21)
 * 그 경로를 되살리지 않는다.
 */
class StockControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/stock";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Autowired private ProductRepository productRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private SellerRepository sellerRepository;

    private Long productId;
    private Long sellerId;

    @BeforeEach
    void seedProduct() {
        productId = productRepository.saveAndFlush(Product.builder().productName("양말A").build()).getId();
        sellerId = sellerRepository.saveAndFlush(Seller.builder()
                .sellerName("셀러A").businessRegistration("111-11-11111").build()).getId();
    }

    private void ledger(StockMovementType type, StockReason reason, int quantity, LocalDate movedOn) {
        ledger(sellerId, type, reason, quantity, movedOn);
    }

    private void ledger(Long owner, StockMovementType type, StockReason reason, int quantity,
                        LocalDate movedOn) {
        stockMovementRepository.saveAndFlush(StockMovement.builder()
                .product(productRepository.findById(productId).orElseThrow())
                .seller(sellerRepository.findById(owner).orElseThrow())
                .movementType(type).quantity(quantity).location(StockLocation.OWN)
                .reason(reason).movedOn(movedOn).createdBy(ADMIN_EMAIL).build());
    }

    private String stockInBody() {
        return "{\"productId\":" + productId + ",\"sellerId\":" + sellerId
                + ",\"movementType\":\"STOCK_IN\",\"quantity\":5,"
                + "\"reason\":\"FREE\",\"movedOn\":\"" + DAY + "\"}";
    }

    @Test
    void testRecordMovementWithAdminToken() throws Exception {
        mockMvc.perform(post(PATH + "/movements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stockInBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.sellerName").value("셀러A"))
                .andExpect(jsonPath("$.data.createdBy").value(ADMIN_EMAIL));
    }

    @Test
    void testRecordMovementWithUserTokenForbidden() throws Exception {
        mockMvc.perform(post(PATH + "/movements")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stockInBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testRecordMovementWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(PATH + "/movements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stockInBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testBalancesReturnsSumOfLedger() throws Exception {
        ledger(StockMovementType.STOCK_IN, StockReason.FREE, 5, DAY);
        ledger(StockMovementType.DISPOSAL, StockReason.DAMAGED, -2, DAY);

        mockMvc.perform(get(PATH + "/balances").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(3));
    }

    @Test
    void testBalancesAllowsNegative() throws Exception {
        ledger(StockMovementType.DISPOSAL, StockReason.LOST, -1, DAY);

        // 음수는 "입력이 빠졌다"는 신호다 — 서버가 0 으로 깎지 않는다.
        mockMvc.perform(get(PATH + "/balances").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(-1));
    }

    @Test
    void testBalancesFilterByKeyword() throws Exception {
        ledger(StockMovementType.STOCK_IN, StockReason.FREE, 1, DAY);

        mockMvc.perform(get(PATH + "/balances").param("keyword", "장갑")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get(PATH + "/balances").param("keyword", "양말")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productName").value("양말A"));
    }

    /** 🔴 Stock is not shared (PLAN 2609_29 D4·D5): the same product split across two sellers = 2 rows. */
    @Test
    void testBalancesSplitBySeller() throws Exception {
        Long other = sellerRepository.saveAndFlush(Seller.builder()
                .sellerName("셀러B").businessRegistration("222-22-22222").build()).getId();
        ledger(StockMovementType.STOCK_IN, StockReason.FREE, 5, DAY);
        ledger(other, StockMovementType.STOCK_IN, StockReason.FREE, 2, DAY);

        mockMvc.perform(get(PATH + "/balances").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get(PATH + "/balances").param("sellerId", other.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].onHand").value(2));
    }

    @Test
    void testHistoryFiltersByPeriod() throws Exception {
        ledger(StockMovementType.STOCK_IN, StockReason.FREE, 4, DAY);
        ledger(StockMovementType.STOCK_IN, StockReason.FREE, 9, DAY.minusMonths(6));

        mockMvc.perform(get(PATH + "/movements")
                        .param("from", DAY.minusDays(7).toString()).param("to", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].quantity").value(4));
    }

    @Test
    void testPurchaseCandidatesWithAdminToken() throws Exception {
        mockMvc.perform(get(PATH + "/purchase-candidates")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + "/purchase-candidates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void testReturnCandidatesWithAdminToken() throws Exception {
        mockMvc.perform(get(PATH + "/return-candidates")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + "/return-candidates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
