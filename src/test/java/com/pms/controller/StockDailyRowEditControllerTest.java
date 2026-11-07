package com.pms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.domain.TenantPurchaseSetting;
import com.pms.migration.UnitPriceFromBaseBackfillRunner;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerCardRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import com.pms.security.TenantContext;
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
import java.util.List;

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
 * FEATURE_2611_17: the daily row popup's one save (D5 · D10 · D17 · D21 · D23 · D26), the unit prices of a row (D1 ·
 * D16), and the empty unit prices filled from the registered price with their 「미기입」 mark (D15 · D22 · D25), on the
 * real schema plus ADMIN authority (401/403).
 */
class StockDailyRowEditControllerTest extends BaseIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final String EDIT = "/api/admin/stock/daily/row-edit";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;
    @Autowired private SellerCardRepository sellerCardRepository;
    @Autowired private UnitPriceFromBaseBackfillRunner backfillRunner;
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
        productId = productRepository.save(Product.builder().productName("팝업양말").barcodeId("8800000000017")
                .price(new BigDecimal("1000")).build()).getId();
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
    }

    private static String line(Long product, int quantity, Long sellerId, String reason) {
        return "{\"productId\":" + product + ",\"quantity\":" + quantity
                + (sellerId == null ? "" : ",\"sellerId\":" + sellerId)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private void intake(String line) throws Exception {
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movedOn\":\"" + DAY + "\",\"lines\":[" + line + "]}")
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

    private JsonNode rows() throws Exception {
        String body = mockMvc.perform(get("/api/admin/stock/daily").param("date", DAY.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("rows");
    }

    private static MockMultipartFile part(String json) {
        return new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("photo", "receipt.png", "image/png", out.toByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions edit(String json, boolean withPhoto) throws Exception {
        var request = multipart(EDIT).file(part(json));
        if (withPhoto) {
            request = request.file(photo());
        }
        return mockMvc.perform(request.param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken));
    }

    @Test
    void rowEdit_권한_401_403() throws Exception {
        mockMvc.perform(multipart(EDIT).file(part("{\"movementId\":1}")).param("movedOn", DAY.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(EDIT).file(part("{\"movementId\":1}")).param("movedOn", DAY.toString())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D5 · D21 · D23 · D26: seller, price and a picked receipt in one save; a refused part leaves nothing behind. */
    @Test
    void testSellerPriceAndPickedReceiptInOneSave() throws Exception {
        intake(line(productId, 2, sellerBId, "PURCHASE"));
        intake(line(productId, 3, sellerAId, "PURCHASE"));
        JsonNode rows = rows();
        long rowB = rows.get(0).get("movementId").asLong();
        long rowA = rows.get(1).get("movementId").asLong();
        long recordA = rows.get(1).get("recordId").asLong();
        Long cardA = sellerCardRepository.save(SellerCard.builder().seller(sellerRepository.findById(sellerAId)
                .orElseThrow()).alias("A카드").maskedNumber("1234-****").build()).getId();

        String made = edit("{\"movementId\":" + rowB + "}", true)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptB = objectMapper.readTree(made).get("data").get("receiptId").asLong();

        // D5: the checks of the request run before the first write — a refused save leaves the seller as it was.
        edit("{\"movementId\":" + rowA + ",\"sellerId\":" + sellerBId + ",\"receiptId\":" + receiptB + ",\"unitPrice\":1,"
                + "\"totalAmount\":3}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("총액과 단가 중 하나만 입력하세요"));
        assertThat(purchaseRecordRepository.findById(recordA).orElseThrow().getSeller().getId()).isEqualTo(sellerAId);
        edit("{\"movementId\":" + rowA + ",\"cardId\":" + cardA + "}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("바꿀 내용이 없습니다."));
        edit("{\"movementId\":" + rowA + ",\"receiptId\":" + receiptB + "}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고른 영수증의 결제 판매자와 판매자가 다릅니다."));
        edit("{\"movementId\":" + rowA + ",\"sellerId\":" + sellerBId + ",\"receiptId\":" + receiptB + "}", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("새 영수증 사진과 고른 영수증을 함께 넣을 수 없습니다."));

        edit("{\"movementId\":" + rowA + ",\"sellerId\":" + sellerBId + ",\"unitPrice\":1500,"
                + "\"reflectToBasePrice\":false,\"receiptId\":" + receiptB + "}", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").value(receiptB));
        purchaseRecordRepository.flush();
        PurchaseRecord moved = purchaseRecordRepository.findById(recordA).orElseThrow();
        assertThat(moved.getSeller().getId()).isEqualTo(sellerBId);
        assertThat(moved.getUnitPrice()).isEqualByComparingTo("1500");
        assertThat(moved.getTotalAmount()).isEqualByComparingTo("4500");
        assertThat(moved.getReceipt().getId()).isEqualTo(receiptB);
        assertThat(productRepository.findById(productId).orElseThrow().getPrice()).isEqualByComparingTo("1000");
        mockMvc.perform(get("/api/admin/purchase-receipts/" + receiptB).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines.length()").value(2));                      // D26: a line of its own
        JsonNode after = rows().get(1);
        assertThat(after.get("sellerName").asText()).isEqualTo("B상사");
        assertThat(after.get("receiptKind").asText()).isEqualTo("RECEIPT");
        assertThat(after.get("sellerChangeable").asBoolean()).isFalse();

        edit("{\"movementId\":" + rowA + ",\"sellerId\":" + sellerBId + "}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("바꿀 내용이 없습니다."));
    }

    /** D5 · D17 · D22 · D12 · D24: an opening-stock price fills the empty registered price and the empty purchases. */
    @Test
    void testOpeningStockPriceFillsEmptyRegisteredPrice() throws Exception {
        Long bare = productRepository.save(Product.builder().productName("무가양말").build()).getId();
        intake(line(bare, 2, sellerAId, "OPENING"));
        intake(line(bare, 1, sellerBId, "PURCHASE"));
        intake(line(bare, 1, null, null));
        JsonNode rows = rows();
        JsonNode opening = rows.get(0);
        assertThat(opening.get("openingPriceEditable").asBoolean()).isTrue();
        assertThat(opening.get("unitPrices").isNull()).isTrue();
        assertThat(rows.get(1).get("openingPriceEditable").asBoolean()).isFalse();
        long openingId = opening.get("movementId").asLong();
        long purchaseRow = rows.get(1).get("movementId").asLong();
        long undecidedRow = rows.get(2).get("movementId").asLong();

        edit("{\"movementId\":" + undecidedRow + ",\"unitPrice\":100}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고칠 수 없는 줄입니다."));
        edit("{\"movementId\":" + openingId + ",\"receiptId\":1}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영수증을 붙일 수 없는 줄입니다."));

        edit("{\"movementId\":" + openingId + ",\"totalAmount\":3000}", false).andExpect(status().isOk());
        assertThat(productRepository.findById(bare).orElseThrow().getPrice()).isEqualByComparingTo("1500");
        rows = rows();
        assertThat(rows.get(0).get("unitPrices").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1500");
        assertThat(rows.get(0).get("unitPrices").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(rows.get(0).get("priceFromBase").asBoolean()).isFalse();
        assertThat(rows.get(1).get("amount").decimalValue()).isEqualByComparingTo("1500");    // D22: filled
        assertThat(rows.get(1).get("priceFromBase").asBoolean()).isTrue();
        assertThat(rows.get(2).get("priceFromBase").asBoolean()).isTrue();

        edit("{\"movementId\":" + openingId + ",\"unitPrice\":2000}", false).andExpect(status().isOk());
        assertThat(productRepository.findById(bare).orElseThrow().getPrice()).isEqualByComparingTo("1500");   // D17
        edit("{\"movementId\":" + purchaseRow + ",\"unitPrice\":1700,\"reflectToBasePrice\":false}", false)
                .andExpect(status().isOk());
        assertThat(rows().get(1).get("priceFromBase").asBoolean()).isFalse();                 // D22: typed
    }

    /** D1 · D16 · D2 · D25: the unit prices of an undecided row and its shares, a share's edit and an 「기타」 intake. */
    @Test
    void testUnitPricesSharesAndEtcIntake() throws Exception {
        intake(line(productId, 3, null, null));
        assignWithout("RECEIPT_LATER", sellerAId, "\"totalAmount\":800,\"reflectToBasePrice\":false");
        assignWithout("NO_RECEIPT", sellerBId, null);
        JsonNode undecided = rows().get(0);
        assertThat(undecided.get("unitPrices").size()).isEqualTo(2);
        assertThat(undecided.get("unitPrices").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(undecided.get("unitPrices").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(undecided.get("unitPrices").get(1).get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(undecided.get("unitPrices").get(1).get("quantity").asInt()).isEqualTo(2);   // share + left, merged
        assertThat(undecided.get("shares").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("800");
        long share = undecided.get("shares").get(1).get("recordId").asLong();

        edit("{\"shareRecordId\":" + share + ",\"sellerId\":" + sellerAId + ",\"unitPrice\":900,"
                + "\"reflectToBasePrice\":false}", false)
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        PurchaseRecord edited = purchaseRecordRepository.findById(share).orElseThrow();
        assertThat(edited.getSeller().getId()).isEqualTo(sellerAId);
        assertThat(edited.getUnitPrice()).isEqualByComparingTo("900");
        long later = undecided.get("shares").get(0).get("recordId").asLong();
        edit("{\"shareRecordId\":" + later + ",\"sellerId\":" + sellerBId + "}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("판매자를 바꿀 수 없는 줄입니다."));            // D19

        mockMvc.perform(post("/api/admin/stock/movements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"sellerId\":" + sellerAId + ",\"movementType\":"
                                + "\"STOCK_IN\",\"quantity\":2,\"reason\":\"ETC\",\"reasonNote\":\"샘플\",\"movedOn\":\""
                                + DAY + "\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        JsonNode etc = rows().get(1);
        assertThat(etc.get("amount").decimalValue()).isEqualByComparingTo("2000");             // D25
        assertThat(etc.get("priceFromBase").asBoolean()).isTrue();
        assertThat(etc.get("unitPrices").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    /** D22: the start-up fill takes every empty unit price of a priced product, once. */
    @Test
    void testStartUpFillOfEmptyUnitPrices() throws Exception {
        Product bare = productRepository.save(Product.builder().productName("늦가양말").build());
        intake(line(bare.getId(), 2, sellerAId, "PURCHASE"));
        productRepository.save(bare.toBuilder().price(new BigDecimal("700")).build());       // no hook on purpose
        productRepository.flush();

        backfillRunner.run(null);
        TenantContext.set(1L);                                       // the runner clears the tenant it set
        List<PurchaseRecord> records = purchaseRecordRepository.findAll().stream()
                .filter(r -> r.getProduct().getId().equals(bare.getId())).toList();
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getUnitPrice()).isEqualByComparingTo("700");
            assertThat(r.getTotalAmount()).isEqualByComparingTo("1400");
            assertThat(r.isPriceFromBase()).isTrue();
            assertThat(r.getReflectToBasePrice()).isFalse();
        });
        assertThat(rows().get(0).get("priceFromBase").asBoolean()).isTrue();
        assertThat(purchaseRecordRepository.findPricedProductIdsWithUnpricedPurchases()).isEmpty();
    }
}
