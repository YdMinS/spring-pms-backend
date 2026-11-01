package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import com.pms.service.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「입고 작업」 → 「그날 입출고 내역」 → receipt assignment (FEATURE_2610_19 D105 · D117 · D119 · D121 · D123 · D130 · D135 ·
 * D147) on the real schema, plus ADMIN authority (401/403). FEATURE_2611_01: intake seller and reason (D1 · D19),
 * assignment without a receipt (D2 · D4 · D12), the later receipt photo (D4) and 「판매자 바꾸기」 (D7 · D15).
 */
class StockDailyControllerTest extends BaseIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;
    @MockBean private ImageStorageService imageStorageService;

    private Long sellerAId;
    private Long sellerBId;
    private Long productId;

    @BeforeEach
    void seed() {
        sellerAId = sellerRepository.save(Seller.builder()
                .sellerName("A상사").businessRegistration("111-11-11111").build()).getId();
        sellerBId = sellerRepository.save(Seller.builder().sellerName("B상사").businessRegistration("222-22-22222")
                .build()).getId();
        productId = productRepository.save(Product.builder().productName("입고양말").barcodeId("8800000000001")
                .price(new BigDecimal("1000")).build()).getId();
    }

    private void stockLinkOn() {
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
    }

    private String intakeBody(int quantity) {
        return "{\"movedOn\":\"" + DAY + "\",\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    private MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("photo", "receipt.png", "image/png", out.toByteArray());
    }

    private MockMultipartFile assignPart(int quantity) {
        String json = "{\"purchasedOn\":\"" + DAY + "\",\"payerSellerId\":" + sellerAId
                + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"unitPrice\":1200}]}";
        return new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void daily_권한_401_403() throws Exception {
        mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D117 ①: the intake screen needs the stock link. */
    @Test
    void testIntakeRejectedWhileStockLinkOff() throws Exception {
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(intakeBody(3))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("재고 연동이 꺼져 있습니다."));
    }

    /** D117 ② ④: the barcode lookup finds the product or says not found. */
    @Test
    void testIntakeBarcodeLookup() throws Exception {
        mockMvc.perform(get("/api/admin/stock/intake/barcode").param("value", "8800000000001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.productName").value("입고양말"));
        mockMvc.perform(get("/api/admin/stock/intake/barcode").param("value", "999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.found").value(false));
    }

    /** D105 · D135 · D147 · D121 · D130: undecided intake → daily → part assigned → the rest stays undecided. */
    @Test
    void testIntakeDailyAndAssign() throws Exception {
        stockLinkOn();
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");

        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(intakeBody(3))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getSeller()).isNull();
            assertThat(r.getUnitPrice()).isEqualByComparingTo("1000");     // D135 ①
            assertThat(r.getReflectToBasePrice()).isFalse();               // D135 ②
        });

        mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unregisteredProductCount").value(1))
                .andExpect(jsonPath("$.data.unregistered[0].remainingQty").value(3))
                .andExpect(jsonPath("$.data.rows[0].undecidedLeft").value(3))
                .andExpect(jsonPath("$.data.rows[0].amount").value(3000));

        mockMvc.perform(multipart("/api/admin/purchase-receipts/assign").file(photo()).file(assignPart(4))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 영수증 미등록 수량(3개)보다 많습니다."));

        mockMvc.perform(multipart("/api/admin/purchase-receipts/assign").file(photo()).file(assignPart(2))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unregisteredProductCount").value(1))
                .andExpect(jsonPath("$.data.unregistered[0].remainingQty").value(1))
                .andExpect(jsonPath("$.data.rows[0].owners[0]").value("A상사 2"))
                .andExpect(jsonPath("$.data.rows[0].undecidedLeft").value(1));
        assertThat(stockMovementRepository.findAll()).hasSize(1);          // D125: no stock row added
    }

    // --- FEATURE_2611_01 ---

    private String intakeLine(int quantity, Long sellerId, String reason) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity
                + (sellerId == null ? "" : ",\"sellerId\":" + sellerId)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private String receiptlessBody(String mode, Long sellerId, int quantity) {
        return "{\"mode\":\"" + mode + "\",\"sellerId\":" + sellerId
                + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    @Test
    void newPaths_권한_401_403() throws Exception {
        String body = receiptlessBody("NO_RECEIPT", sellerAId, 1);
        mockMvc.perform(post("/api/admin/purchase-receipts/assign-without-receipt").param("movedOn", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/purchase-receipts/assign-without-receipt").param("movedOn", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/admin/purchase-receipts/1/photo").file(photo()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/admin/purchase-receipts/1/photo").file(photo())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        String change = "{\"sellerId\":" + sellerAId + ",\"movementIds\":[1]}";
        mockMvc.perform(post("/api/admin/stock/daily/change-seller").contentType(MediaType.APPLICATION_JSON)
                        .content(change))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/stock/daily/change-seller").contentType(MediaType.APPLICATION_JSON)
                        .content(change).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D1 · D13 · D19: per-line seller and reason; an opening line without a seller stops the whole save. */
    @Test
    void testIntakeSellerAndReason() throws Exception {
        stockLinkOn();
        String blocked = "{\"movedOn\":\"" + DAY + "\",\"lines\":[" + intakeLine(1, sellerAId, null) + ","
                + intakeLine(2, null, "OPENING") + "]}";
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(blocked)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("기초재고는 판매자를 골라야 합니다: 입고양말"));
        assertThat(stockMovementRepository.findAll()).isEmpty();

        String body = "{\"movedOn\":\"" + DAY + "\",\"lines\":[" + intakeLine(2, sellerAId, "PURCHASE") + ","
                + intakeLine(1, sellerBId, "OPENING") + "," + intakeLine(3, null, null) + "]}";
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findAll()).extracting(r -> r.getSeller() == null ? null : r.getSeller().getId())
                .containsExactlyInAnyOrder(sellerAId, null);
        assertThat(stockMovementRepository.findAll()).extracting(m -> m.getReason().name())
                .containsExactlyInAnyOrder("PURCHASE", "OPENING", "PURCHASE");

        mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].sellerChangeable").value(true))
                .andExpect(jsonPath("$.data.rows[1].sellerChangeable").value(true))
                .andExpect(jsonPath("$.data.rows[2].sellerChangeable").value(false));
    }

    /** D2 · D4 · D12 · D15 · D7: later receipt + found stock, the photo added later, then a share and a row move. */
    @Test
    void testAssignWithoutReceiptThenChangeSeller() throws Exception {
        stockLinkOn();
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(intakeBody(3))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String later = mockMvc.perform(post("/api/admin/purchase-receipts/assign-without-receipt")
                        .param("movedOn", DAY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(receiptlessBody("RECEIPT_LATER", sellerAId, 1))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(later).get("data").get("receiptId").asLong();
        mockMvc.perform(post("/api/admin/purchase-receipts/assign-without-receipt")
                        .param("movedOn", DAY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(receiptlessBody("NO_RECEIPT", sellerBId, 1))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").doesNotExist());

        String daily = mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].undecidedLeft").value(1))
                .andExpect(jsonPath("$.data.rows[0].noReceiptShares.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].noReceiptShares[0].sellerName").value("B상사"))
                .andReturn().getResponse().getContentAsString();
        long shareId = objectMapper.readTree(daily).get("data").get("rows").get(0).get("noReceiptShares").get(0)
                .get("recordId").asLong();
        mockMvc.perform(get("/api/admin/purchase-receipts").param("from", DAY.toString()).param("to", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hasPhoto").value(false));

        mockMvc.perform(multipart("/api/admin/purchase-receipts/" + receiptId + "/photo").file(photo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photoUrl").value("https://bucket/receipts/r.png"));
        mockMvc.perform(multipart("/api/admin/purchase-receipts/" + receiptId + "/photo").file(photo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 사진이 있는 영수증입니다."));

        mockMvc.perform(post("/api/admin/stock/daily/change-seller").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":" + sellerAId + ",\"shareRecordIds\":[" + shareId + "]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findById(shareId).orElseThrow()).satisfies(r -> {
            assertThat(r.getSeller().getId()).isEqualTo(sellerAId);
            assertThat(r.getFoundStockAt()).isNotNull();                     // D12: the mark stays
        });
        mockMvc.perform(get("/api/admin/purchase-list/purchases").param("productId", String.valueOf(productId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].foundStock").value(true))
                .andExpect(jsonPath("$.data[1].foundStock").value(false));

        Long undecidedRowId = stockMovementRepository.findAll().get(0).getId();
        mockMvc.perform(post("/api/admin/stock/daily/change-seller").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":" + sellerAId + ",\"movementIds\":[" + undecidedRowId + "]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("판매자를 바꿀 수 없는 줄입니다."));
    }

    /** D7: a decided row without a receipt moves with its purchase record. */
    @Test
    void testChangeSellerMovesRowAndRecord() throws Exception {
        stockLinkOn();
        String body = "{\"movedOn\":\"" + DAY + "\",\"lines\":[" + intakeLine(2, sellerAId, "PURCHASE") + "]}";
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        Long rowId = stockMovementRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/admin/stock/daily/change-seller").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":" + sellerBId + ",\"movementIds\":[" + rowId + "]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findById(rowId).orElseThrow().getSeller().getId()).isEqualTo(sellerBId);
        assertThat(purchaseRecordRepository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getSeller().getId()).isEqualTo(sellerBId));
    }
}
