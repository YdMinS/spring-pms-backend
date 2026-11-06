package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerCardRepository;
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
 * FEATURE_2611_16: 「매입가 넣기」 — amounts and the registered price (D3 · D6 · D7), the share lines (D8), the photo as
 * a new receipt or on a 「영수증 나중에」 receipt (D2 · D11 ~ D13 · D15 · D16) and receipt lines (D17 · D18).
 */
class StockDailyControllerTest extends BaseIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;
    @Autowired private SellerCardRepository sellerCardRepository;
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
                .andExpect(jsonPath("$.data.rows[0].shares.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].shares[0].sellerChangeable").value(false))
                .andExpect(jsonPath("$.data.rows[0].shares[1].sellerName").value("B상사"))
                .andExpect(jsonPath("$.data.rows[0].shares[1].sellerChangeable").value(true))
                .andReturn().getResponse().getContentAsString();
        long shareId = objectMapper.readTree(daily).get("data").get("rows").get(0).get("shares").get(1)
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

    // --- FEATURE_2611_16 ---

    private MockMultipartFile pricePart(String lines, Long cardId) {
        String json = "{" + (cardId == null ? "" : "\"cardId\":" + cardId + ",") + "\"lines\":[" + lines + "]}";
        return new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String priceLine(long recordId, String amount, Boolean reflect) {
        return "{\"recordId\":" + recordId + (amount == null ? "" : "," + amount)
                + (reflect == null ? "" : ",\"reflectToBasePrice\":" + reflect) + "}";
    }

    private JsonNode dailyRows(LocalDate day) throws Exception {
        String body = mockMvc.perform(get("/api/admin/stock/daily").param("date", day.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("rows");
    }

    private void intakeOn(LocalDate day, String line) throws Exception {
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movedOn\":\"" + day + "\",\"lines\":[" + line + "]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private void assignWithout(String mode, Long sellerId, String amount) throws Exception {
        mockMvc.perform(post("/api/admin/purchase-receipts/assign-without-receipt").param("movedOn", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"" + mode + "\",\"sellerId\":" + sellerId + ",\"lines\":[{\"productId\":"
                                + productId + ",\"quantity\":1" + (amount == null ? "" : "," + amount) + "}]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void purchasePrice_권한_401_403() throws Exception {
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(pricePart(priceLine(1, null, null), null))
                        .param("movedOn", DAY.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(pricePart(priceLine(1, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D1 · D3 · D6 · D7: a decided row's purchase and stock row take the amount; the base price follows the latest only. */
    @Test
    void testRetypeDecidedRowMovesBasePriceOnlyWhenLatest() throws Exception {
        stockLinkOn();
        intakeOn(DAY, intakeLine(2, sellerAId, "PURCHASE"));
        intakeOn(DAY.plusDays(1), intakeLine(1, sellerAId, "PURCHASE"));
        JsonNode row = dailyRows(DAY).get(0);
        long first = row.get("recordId").asLong();
        assertThat(row.get("receiptKind").asText()).isEqualTo("NO_RECEIPT");
        assertThat(row.get("sellerId").asLong()).isEqualTo(sellerAId);
        long second = dailyRows(DAY.plusDays(1)).get(0).get("recordId").asLong();

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price")
                        .file(pricePart(priceLine(first, "\"totalAmount\":3000", true), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").doesNotExist());
        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findById(first).orElseThrow()).satisfies(r -> {
            assertThat(r.getUnitPrice()).isEqualByComparingTo("1500");
            assertThat(r.getTotalAmount()).isEqualByComparingTo("3000");
            assertThat(r.getReflectToBasePrice()).isTrue();
        });
        assertThat(productRepository.findById(productId).orElseThrow().getPrice()).isEqualByComparingTo("1500");
        assertThat(dailyRows(DAY).get(0).get("amount").decimalValue()).isEqualByComparingTo("3000");

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price")
                        .file(pricePart(priceLine(second, "\"unitPrice\":2000", true), null))
                        .param("movedOn", DAY.plusDays(1).toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price")
                        .file(pricePart(priceLine(first, "\"unitPrice\":1200", true), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        assertThat(productRepository.findById(productId).orElseThrow().getPrice()).isEqualByComparingTo("2000");
        assertThat(purchaseRecordRepository.findById(first).orElseThrow()).satisfies(r -> {
            assertThat(r.getUnitPrice()).isEqualByComparingTo("1200");
            assertThat(r.getReflectToBasePrice()).isFalse();                // D3: a newer purchase exists
        });
        assertThat(stockMovementRepository.findByPurchaseRecordIdIn(java.util.List.of(first)))
                .singleElement().satisfies(m -> assertThat(m.getUnitPrice()).isEqualByComparingTo("1200"));

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(pricePart(priceLine(first, null, true), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("바꿀 금액을 넣거나 영수증 사진을 올리세요."));
    }

    /** D8 · D2 · D11 · D12 · D13 · D15 · D16 · D17 · D18 · D10: share lines, then a photo makes a new receipt. */
    @Test
    void testShareLinesAndPhotoMakesNewReceipt() throws Exception {
        stockLinkOn();
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
        intakeOn(DAY, intakeLine(3, null, null));
        assignWithout("RECEIPT_LATER", sellerAId, "\"totalAmount\":800,\"reflectToBasePrice\":false");
        assignWithout("NO_RECEIPT", sellerBId, null);
        intakeOn(DAY, intakeLine(2, sellerAId, "PURCHASE"));
        Long cardA = sellerCardRepository.save(SellerCard.builder().seller(sellerRepository.findById(sellerAId)
                .orElseThrow()).alias("A카드").maskedNumber("1234-****").build()).getId();
        Long cardB = sellerCardRepository.save(SellerCard.builder().seller(sellerRepository.findById(sellerBId)
                .orElseThrow()).alias("B카드").maskedNumber("5678-****").build()).getId();

        JsonNode rows = dailyRows(DAY);
        JsonNode undecided = rows.get(0);
        assertThat(undecided.get("amount").decimalValue()).isEqualByComparingTo("2800");    // D8: 800 + 1000 + 1 × 1000
        assertThat(undecided.get("recordId").isNull()).isTrue();                             // D10
        assertThat(undecided.get("shares").get(0).get("receiptKind").asText()).isEqualTo("RECEIPT_LATER");
        assertThat(undecided.get("shares").get(0).get("amount").decimalValue()).isEqualByComparingTo("800");
        assertThat(undecided.get("shares").get(1).get("receiptKind").asText()).isEqualTo("NO_RECEIPT");
        long later = undecided.get("shares").get(0).get("recordId").asLong();
        long found = undecided.get("shares").get(1).get("recordId").asLong();
        long rowA = rows.get(1).get("recordId").asLong();
        Long undecidedRecord = purchaseRecordRepository.findAll().stream().filter(r -> r.getSeller() == null)
                .filter(r -> r.getQuantity() > 0).findFirst().orElseThrow().getId();

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price")
                        .file(pricePart(priceLine(undecidedRecord, "\"unitPrice\":900", null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("매입가를 넣을 수 없는 줄입니다."));
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(found, null, null) + "," + priceLine(rowA, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사진을 넣으면 같은 판매자 입고만 묶을 수 있습니다."));
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(later, null, null) + "," + priceLine(rowA, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사진은 같은 영수증의 「영수증 나중에」 입고만 체크했을 때 올릴 수 있습니다."));
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(found, null, null), cardA))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 입고 판매자의 카드가 아닙니다."));

        String saved = mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(found, "\"totalAmount\":900", false), cardB))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(saved).get("data").get("receiptId").asLong();
        purchaseRecordRepository.flush();
        PurchaseRecord bound = purchaseRecordRepository.findById(found).orElseThrow();
        assertThat(bound.getReceipt().getId()).isEqualTo(receiptId);
        assertThat(bound.getFoundStockAt()).isNull();                                       // D2
        assertThat(bound.getTotalAmount()).isEqualByComparingTo("900");
        mockMvc.perform(get("/api/admin/purchase-receipts/" + receiptId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchasedOn").value(DAY.toString()))            // D15
                .andExpect(jsonPath("$.data.payerSellerId").value(sellerBId))
                .andExpect(jsonPath("$.data.cardId").value(cardB))
                .andExpect(jsonPath("$.data.photoUrl").value("https://bucket/receipts/r.png"));
        JsonNode share = dailyRows(DAY).get(0).get("shares").get(1);
        assertThat(share.get("receiptKind").asText()).isEqualTo("RECEIPT");
        assertThat(share.get("sellerChangeable").asBoolean()).isFalse();                  // D15

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(found, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사진 있는 영수증 입고가 있어 사진을 올릴 수 없습니다."));
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price")
                        .file(pricePart(priceLine(found, "\"unitPrice\":950", false), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/purchase-receipts/" + receiptId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].totalAmount").value(950));             // D17
    }

    /** D13: only 「영수증 나중에」 lines of one receipt → the photo goes to that receipt. */
    @Test
    void testPhotoGoesToTheReceiptLaterReceipt() throws Exception {
        stockLinkOn();
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
        intakeOn(DAY, intakeLine(3, null, null));
        assignWithout("RECEIPT_LATER", sellerAId, null);
        assignWithout("RECEIPT_LATER", sellerAId, null);
        JsonNode shares = dailyRows(DAY).get(0).get("shares");
        long first = shares.get(0).get("recordId").asLong();
        long second = shares.get(1).get("recordId").asLong();
        long firstReceipt = shares.get(0).get("receiptId").asLong();

        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(first, null, null) + "," + priceLine(second, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사진은 같은 영수증의 「영수증 나중에」 입고만 체크했을 때 올릴 수 있습니다."));
        mockMvc.perform(multipart("/api/admin/stock/daily/purchase-price").file(photo())
                        .file(pricePart(priceLine(first, null, null), null))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").value(firstReceipt));
        mockMvc.perform(get("/api/admin/purchase-receipts/" + firstReceipt).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photoUrl").value("https://bucket/receipts/r.png"));
        assertThat(dailyRows(DAY).get(0).get("shares").get(0).get("receiptKind").asText()).isEqualTo("RECEIPT");
    }
}
