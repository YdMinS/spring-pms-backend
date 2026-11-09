package com.pms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
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
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementDateEditRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.repository.TenantPurchaseSettingRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.stock.LotLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEATURE_2611_21: the day correction of 「일별 입출고 내역」 rows (D4 · D5 · D6 · D7 · D9 · D12 · D13 · D20 · D22 · D24 ·
 * D25 · D27 · D28 · D34) and of the receipt header edit (D26 · D31 · D32), on the real schema plus ADMIN authority
 * (401/403).
 */
class StockDailyDateEditControllerTest extends BaseIntegrationTest {

    private static final String MOVE = "/api/admin/stock/daily/move-date";
    private static final String EDIT = "/api/admin/stock/daily/row-edit";
    private static final String RECEIPTS = "/api/admin/purchase-receipts";
    private static final LocalDate ORDER_DAY = LocalDate.of(2026, 11, 1);
    private static final LocalDate LOT_DAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final LocalDate OUT_DAY = LocalDate.of(2026, 11, 4);
    private static final LocalDate LATER_DAY = LocalDate.of(2026, 11, 5);

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private PurchaseReceiptRepository purchaseReceiptRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private StockMovementDateEditRepository stockMovementDateEditRepository;
    @Autowired private SellerDebtRepository sellerDebtRepository;
    @Autowired private TenantPurchaseSettingRepository tenantPurchaseSettingRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private LotLedgerService lotLedgerService;
    @MockBean private ImageStorageService imageStorageService;

    private Seller sellerA;
    private Seller sellerB;
    private Product product;

    @BeforeEach
    void seed() {
        sellerA = sellerRepository.save(Seller.builder()
                .sellerName("A상사").businessRegistration("111-11-11111").build());
        sellerB = sellerRepository.save(Seller.builder()
                .sellerName("B상사").businessRegistration("222-22-22222").build());
        product = productRepository.save(Product.builder().productName("날짜양말").barcodeId("8800000000024")
                .price(new BigDecimal("1000")).build());
        tenantPurchaseSettingRepository.save(TenantPurchaseSetting.builder().stockLinked(true).build());
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
    }

    private ResultActions move(long movementId, LocalDate day) throws Exception {
        return mockMvc.perform(post(MOVE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"movementId\":" + movementId + ",\"newMovedOn\":\"" + day + "\"}")
                .header("Authorization", "Bearer " + adminToken));
    }

    private void intake(Long productId, int quantity, Long sellerId, String reason, LocalDate day) throws Exception {
        mockMvc.perform(post("/api/admin/stock/intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movedOn\":\"" + day + "\",\"lines\":[{\"productId\":" + productId
                                + ",\"quantity\":" + quantity + (sellerId == null ? "" : ",\"sellerId\":" + sellerId)
                                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private void receiptless(String mode, Long sellerId, String lines) throws Exception {
        mockMvc.perform(post(RECEIPTS + "/assign-without-receipt").param("movedOn", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"" + mode + "\",\"sellerId\":" + sellerId + ",\"lines\":[" + lines + "]}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private JsonNode daily(LocalDate day) throws Exception {
        String body = mockMvc.perform(get("/api/admin/stock/daily").param("date", day.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
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

    private ResultActions edit(String json, LocalDate screenDay, boolean withPhoto) throws Exception {
        var request = multipart(EDIT).file(part(json));
        if (withPhoto) {
            request = request.file(photo());
        }
        return mockMvc.perform(request.param("movedOn", screenDay.toString())
                .header("Authorization", "Bearer " + adminToken));
    }

    private StockMovement row(StockMovementType type, Seller seller, int quantity, LocalDate day, StockMovement source,
                              OrderLine line) {
        return stockMovementRepository.save(StockMovement.builder().product(product).seller(seller).movementType(type)
                .reason(type == StockMovementType.STOCK_IN ? StockReason.PURCHASE
                        : type == StockMovementType.DISPOSAL ? StockReason.DAMAGED : null)
                .location(StockLocation.OWN).quantity(quantity).unitPrice(source == null ? new BigDecimal("1000") : null)
                .sourceMovement(source).orderLine(line).movedOn(day).build());
    }

    private OrderLine orderLine(MarketplaceAccount account, String orderId) {
        Order order = orderRepository.save(Order.builder().marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(orderId).orderedAt(ORDER_DAY.atTime(10, 0)).build());
        return orderLineRepository.save(OrderLine.builder().order(order).status(OrderStatus.PAID).itemName("날짜양말")
                .orderQty(2).cancelQty(0).holdQty(0).build());
    }

    @Test
    void moveDate_권한_401_403() throws Exception {
        String body = "{\"movementId\":1,\"newMovedOn\":\"" + DAY + "\"}";
        mockMvc.perform(post(MOVE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(MOVE).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D4 · D5 · D6 · D7 · D20 · D22 · D24 · D27 · D34: an order outbound moves with its order and its debts follow. */
    @Test
    void testOrderOutboundMovesWithItsOrderAndRewritesDebts() throws Exception {
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(sellerA).build());
        OrderLine first = orderLine(account, "ORD-2621-1");
        OrderLine second = orderLine(account, "ORD-2621-2");
        StockMovement lot = row(StockMovementType.STOCK_IN, sellerB, 5, LOT_DAY, null, null);
        StockMovement outA = row(StockMovementType.STOCK_OUT, sellerB, -1, OUT_DAY, lot, first);
        StockMovement outB = row(StockMovementType.STOCK_OUT, sellerB, -1, OUT_DAY, lot, first);
        StockMovement other = row(StockMovementType.STOCK_OUT, sellerB, -1, OUT_DAY, lot, second);
        lotLedgerService.rebuildDebts(product.getId());

        move(outA.getId(), ORDER_DAY.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("주문일(11월 1일)보다 앞으로 옮길 수 없습니다."));
        move(outA.getId(), ORDER_DAY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("11월 2일 입고에서 나간 줄이라 그보다 앞으로 옮길 수 없습니다."));
        move(outA.getId(), LocalDate.now(SyncWindow.KST).plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("오늘 이후 날짜로는 옮길 수 없습니다."));
        move(lot.getId(), LATER_DAY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 입고에서 11월 4일에 나간 기록이 있어 그보다 뒤로 옮길 수 없습니다."));

        move(outA.getId(), LATER_DAY).andExpect(status().isOk());
        stockMovementRepository.flush();
        assertThat(stockMovementRepository.findById(outB.getId()).orElseThrow().getMovedOn()).isEqualTo(LATER_DAY);
        assertThat(stockMovementRepository.findById(other.getId()).orElseThrow().getMovedOn()).isEqualTo(OUT_DAY);
        assertThat(sellerDebtRepository.findAll()).hasSize(3).allSatisfy(debt -> assertThat(debt.getMovedOn())
                .isEqualTo(debt.getOutMovement().getId().equals(other.getId()) ? OUT_DAY : LATER_DAY));
        assertThat(stockMovementDateEditRepository.findAll()).hasSize(2).allSatisfy(edit -> {
            assertThat(edit.getOldMovedOn()).isEqualTo(OUT_DAY);
            assertThat(edit.getNewMovedOn()).isEqualTo(LATER_DAY);
            assertThat(edit.getEditedBy()).isEqualTo(ADMIN_EMAIL);
        });
        JsonNode moved = daily(LATER_DAY).get("rows");
        assertThat(moved).hasSize(2);
        assertThat(moved.get(0).get("sourceMovementId").asLong()).isEqualTo(lot.getId());
        assertThat(moved.get(0).get("sourceMovedOn").asText()).isEqualTo("2026-11-02");
        assertThat(moved.get(0).get("sourcePriceEditable").asBoolean()).isFalse();             // D20: no record
        assertThat(moved.get(0).get("dateEdits").get(0).get("oldMovedOn").asText()).isEqualTo("2026-11-04");

        move(lot.getId(), DAY).andExpect(status().isOk());                    // D27: not after 11-04, the first out
        JsonNode lotRow = daily(DAY).get("rows").get(0);
        assertThat(lotRow.get("sourceMovementId").isNull()).isTrue();
        assertThat(lotRow.get("dateEdits").get(0).get("newMovedOn").asText()).isEqualTo("2026-11-03");
    }

    /** D9 · D12 · D28: a purchase row moves its record; the base price follows a new latest mover; a photo blocks. */
    @Test
    void testPurchaseRowMovesItsRecordAndRedecidesBasePrice() throws Exception {
        intake(product.getId(), 2, sellerA.getId(), "PURCHASE", DAY);
        intake(product.getId(), 1, sellerA.getId(), "PURCHASE", OUT_DAY);
        JsonNode firstRow = daily(DAY).get("rows").get(0);
        long rowId = firstRow.get("movementId").asLong();
        long recordId = firstRow.get("recordId").asLong();
        long secondRow = daily(OUT_DAY).get("rows").get(0).get("movementId").asLong();
        edit("{\"movementId\":" + rowId + ",\"unitPrice\":1500,\"reflectToBasePrice\":true}", DAY, false)
                .andExpect(status().isOk());
        edit("{\"movementId\":" + secondRow + ",\"unitPrice\":2000,\"reflectToBasePrice\":true}", OUT_DAY, false)
                .andExpect(status().isOk());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("2000");

        edit("{\"movementId\":" + rowId + ",\"newMovedOn\":\"" + LATER_DAY + "\"}", DAY, false)
                .andExpect(status().isOk());
        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().getPurchasedOn()).isEqualTo(LATER_DAY);
        assertThat(stockMovementRepository.findById(rowId).orElseThrow().getMovedOn()).isEqualTo(LATER_DAY);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice())
                .isEqualByComparingTo("1500");                                                  // D28: new latest
        edit("{\"movementId\":" + rowId + ",\"newMovedOn\":\"" + DAY + "\"}", LATER_DAY, false)
                .andExpect(status().isOk());
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("2000");

        edit("{\"movementId\":" + rowId + ",\"newMovedOn\":\"" + DAY + "\"}", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("바꿀 내용이 없습니다."));                 // D9: same day
        edit("{\"movementId\":" + rowId + ",\"newMovedOn\":\"" + OUT_DAY + "\"}", DAY, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영수증에 묶인 입고는 날짜를 옮길 수 없습니다."));
        edit("{\"movementId\":" + rowId + "}", DAY, true).andExpect(status().isOk());
        move(rowId, OUT_DAY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영수증에 묶인 입고는 날짜를 옮길 수 없습니다."));
    }

    /** D13 · D25: an undecided row takes its record, shares, minus rows and 「영수증 나중에」 receipt to the new day. */
    @Test
    void testUndecidedRowMovesItsSharesMinusRowsAndLaterReceipt() throws Exception {
        intake(product.getId(), 3, null, null, DAY);
        String one = "{\"productId\":" + product.getId() + ",\"quantity\":1}";
        receiptless("NO_RECEIPT", sellerA.getId(), one);
        receiptless("RECEIPT_LATER", sellerB.getId(), one);
        JsonNode undecided = daily(DAY).get("rows").get(0);
        long undecidedId = undecided.get("movementId").asLong();
        long share = undecided.get("shares").get(0).get("recordId").asLong();

        edit("{\"shareRecordId\":" + share + ",\"newMovedOn\":\"" + LOT_DAY + "\"}", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고칠 수 없는 줄입니다."));                 // D13: no own day
        move(undecidedId, LOT_DAY).andExpect(status().isOk());
        purchaseRecordRepository.flush();
        List<PurchaseRecord> records = purchaseRecordRepository.findAll().stream()
                .filter(r -> r.getProduct().getId().equals(product.getId())).toList();
        assertThat(records).hasSize(5).allSatisfy(r -> assertThat(r.getPurchasedOn()).isEqualTo(LOT_DAY));
        assertThat(purchaseReceiptRepository.findAll()).singleElement()
                .satisfies(receipt -> assertThat(receipt.getPurchasedOn()).isEqualTo(LOT_DAY));
        JsonNode moved = daily(LOT_DAY);
        assertThat(moved.get("unregisteredProductCount").asInt()).isEqualTo(1);
        assertThat(moved.get("unregistered").get(0).get("remainingQty").asInt()).isEqualTo(1);
        assertThat(moved.get("rows").get(0).get("dateEdits")).hasSize(1);
        assertThat(daily(DAY).get("unregisteredProductCount").asInt()).isZero();

        Long sock2 = productRepository.save(Product.builder().productName("날짜양말2").barcodeId("8800000000031")
                .price(new BigDecimal("1000")).build()).getId();
        intake(sock2, 2, null, null, DAY);
        intake(product.getId(), 1, null, null, DAY);
        receiptless("RECEIPT_LATER", sellerA.getId(),
                one + ",{\"productId\":" + sock2 + ",\"quantity\":1}");
        long sock2Row = daily(DAY).get("rows").get(0).get("movementId").asLong();
        move(sock2Row, LOT_DAY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 영수증에 다른 입고가 함께 있어 날짜를 옮길 수 없습니다."));
        String assign = "{\"purchasedOn\":\"" + DAY + "\",\"payerSellerId\":" + sellerB.getId()
                + ",\"lines\":[{\"productId\":" + sock2 + ",\"quantity\":1,\"unitPrice\":1200}]}";
        mockMvc.perform(multipart(RECEIPTS + "/assign").file(photo()).file(part(assign))
                        .param("movedOn", DAY.toString()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        move(sock2Row, LOT_DAY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영수증에 묶인 몫이 있어 날짜를 옮길 수 없습니다."));
    }

    /** D26 · D31 · D32: the receipt header day moves the receipt's stock rows within the first-out limit. */
    @Test
    void testReceiptDayEditMovesItsRowsWithinLimits() throws Exception {
        String create = "{\"purchasedOn\":\"" + DAY + "\",\"payerSellerId\":" + sellerA.getId()
                + ",\"lines\":[{\"productId\":" + product.getId() + ",\"quantity\":2}]}";
        String created = mockMvc.perform(multipart(RECEIPTS).file(photo()).file(part(create))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(created).get("data").get("receiptId").asLong();
        long lotId = daily(DAY).get("rows").get(0).get("movementId").asLong();
        String tomorrow = LocalDate.now(SyncWindow.KST).plusDays(1).toString();

        mockMvc.perform(put(RECEIPTS + "/" + receiptId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedOn\":\"" + tomorrow + "\",\"payerSellerId\":" + sellerA.getId() + "}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("구매일은 오늘 이후로 정할 수 없습니다."));
        mockMvc.perform(put(RECEIPTS + "/" + receiptId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedOn\":\"" + LOT_DAY + "\",\"payerSellerId\":" + sellerA.getId() + "}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        stockMovementRepository.flush();
        StockMovement lot = stockMovementRepository.findById(lotId).orElseThrow();
        assertThat(lot.getMovedOn()).isEqualTo(LOT_DAY);
        assertThat(stockMovementDateEditRepository.findAll()).singleElement()
                .satisfies(edit -> assertThat(edit.getOldMovedOn()).isEqualTo(DAY));

        row(StockMovementType.DISPOSAL, sellerA, -1, OUT_DAY, lot, null);
        mockMvc.perform(put(RECEIPTS + "/" + receiptId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedOn\":\"" + LATER_DAY + "\",\"payerSellerId\":" + sellerA.getId() + "}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("날짜양말: 이 입고에서 11월 4일에 나간 기록이 있어 그보다 뒤로 옮길 수 없습니다."));
    }
}
