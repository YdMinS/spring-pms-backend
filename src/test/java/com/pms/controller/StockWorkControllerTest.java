package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.StockMovementType;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「출고 작업」 save (FEATURE_2611_34 D35 ⑤) on the real schema: ADMIN authority (401/403), the stock-link gate (D4),
 * disposal and 「매입처 반품」 rows (D5), and the over-stock refusal named by product (D15 · R4).
 */
class StockWorkControllerTest extends BaseIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;

    private Long sellerAId;
    private Long productId;

    @BeforeEach
    void seed() {
        sellerAId = sellerRepository.save(Seller.builder()
                .sellerName("A상사").businessRegistration("111-11-11111").build()).getId();
        productId = productRepository.save(Product.builder().productName("입고양말").barcodeId("8800000000001")
                .price(new BigDecimal("1000")).build()).getId();
    }

    private void stockLinkOn() {
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
    }

    /** Opening stock for seller A through 「입고 작업」. */
    private void openingStock(int quantity) throws Exception {
        String body = "{\"movedOn\":\"" + DAY + "\",\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity
                + ",\"sellerId\":" + sellerAId + ",\"reason\":\"OPENING\"}]}";
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private String line(String reason, int quantity) {
        return "{\"productId\":" + productId + ",\"sellerId\":" + sellerAId + ",\"reason\":\"" + reason
                + "\",\"quantity\":" + quantity + "}";
    }

    private String body(String... lines) {
        return "{\"movedOn\":\"" + DAY + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    @Test
    void outboundWork_권한_401_403() throws Exception {
        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 1))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 1)))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D4: the work screens need the stock link. */
    @Test
    void testOutboundWorkRejectedWhileStockLinkOff() throws Exception {
        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 1)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("재고 연동이 꺼져 있습니다."));
    }

    /** D5 · D6: a disposal and a 「매입처 반품」 line are both disposal rows of the line seller. */
    @Test
    void testOutboundWorkSavesDisposalAndSupplierReturn() throws Exception {
        stockLinkOn();
        openingStock(5);

        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 1), line("SUPPLIER_RETURN", 2)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findAll())
                .filteredOn(m -> m.getMovementType() == StockMovementType.DISPOSAL)
                .extracting(m -> m.getQuantity(), m -> m.getReason().name(), m -> m.getSeller().getId())
                .containsExactlyInAnyOrder(
                        tuple(-1, "DAMAGED", sellerAId),
                        tuple(-2, "SUPPLIER_RETURN", sellerAId));
    }

    /** D15 · R4: over the remaining stock → 400 named by the product, nothing written. */
    @Test
    void testOutboundWorkOverStockNamesProduct() throws Exception {
        stockLinkOn();
        openingStock(1);

        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 2)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("입고양말: 남은 재고 수량(1개)보다 많습니다."));

        assertThat(stockMovementRepository.findAll())
                .filteredOn(m -> m.getMovementType() == StockMovementType.DISPOSAL)
                .isEmpty();
    }

    /**
     * D15: lines of the same product and seller are checked together — the second line meets what the first left.
     * Row counts are not asserted: the test-class transaction hides the rollback.
     */
    @Test
    void testOutboundWorkSumsLinesOfSameProduct() throws Exception {
        stockLinkOn();
        openingStock(3);

        mockMvc.perform(post("/api/admin/stock/outbound-work").contentType(MediaType.APPLICATION_JSON)
                        .content(body(line("DAMAGED", 2), line("LOST", 2)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("입고양말: 남은 재고 수량(1개)보다 많습니다."));
    }
}
