package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.repository.ProductRepository;
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
 * 경로는 {@code /api/admin/stock} 이다 — {@code /api/stock} 은 레거시 StockLog 가 아직 점유 중이며,
 * 그쪽이 제거된 뒤에도 이 경로를 유지한다.
 */
class StockControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/stock";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Autowired private ProductRepository productRepository;
    @Autowired private StockMovementRepository stockMovementRepository;

    private Long productId;

    @BeforeEach
    void seedProduct() {
        productId = productRepository.saveAndFlush(Product.builder().productName("양말A").build()).getId();
    }

    private void ledger(StockMovementType type, StockReason reason, int quantity, LocalDate movedOn) {
        stockMovementRepository.saveAndFlush(StockMovement.builder()
                .product(productRepository.findById(productId).orElseThrow())
                .movementType(type).quantity(quantity).location(StockLocation.OWN)
                .reason(reason).movedOn(movedOn).createdBy(ADMIN_EMAIL).build());
    }

    private String stockInBody() {
        return "{\"productId\":" + productId + ",\"movementType\":\"STOCK_IN\",\"quantity\":5,"
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
