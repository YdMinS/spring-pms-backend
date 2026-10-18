package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PurchaseReceiptController — ADMIN authority (401/403) + receipt check-in happy path (payer seller owns the lines,
 * stock link off = no stock) + photo required 400 + list/detail/history + header edit (FEATURE_2610_18 D76 · D87~D89 ·
 * D93 · D94 · D100).
 * {@link ImageStorageService} is stubbed (no disk/network); {@code ImageValidator} runs on a real PNG.
 */
class PurchaseReceiptControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseReceiptRepository purchaseReceiptRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @MockBean private ImageStorageService imageStorageService;

    private static final String PATH = "/api/admin/purchase-receipts";

    private Long sellerId;
    private Long productId;

    @BeforeEach
    void seed() {
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("영수증셀러").businessRegistration("333-44-55555").build()).getId();
        productId = productRepository.save(Product.builder().productName("영수증양말").build()).getId();
    }

    private MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("photo", "receipt.png", "image/png", out.toByteArray());
    }

    private MockMultipartFile requestPart(String totalAmount) {
        String json = "{\"purchasedOn\":\"" + LocalDate.now() + "\",\"payerSellerId\":" + sellerId
                + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":3"
                + (totalAmount == null ? "" : ",\"totalAmount\":" + totalAmount) + "}]}";
        return new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void create_권한_401_403() throws Exception {
        mockMvc.perform(multipart(PATH).file(photo()).file(requestPart(null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(PATH).file(photo()).file(requestPart(null))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** D87 · D89 · D93 · D94: one line owned by the picked payer seller, linked to the receipt; no stock (link off). */
    @Test
    void testCreateReceiptThenListDetailAndHistory() throws Exception {
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");

        String created = mockMvc.perform(multipart(PATH).file(photo()).file(requestPart("9000"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(created).get("data").get("receiptId").asLong();

        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getSeller().getId()).isEqualTo(sellerId);
            assertThat(r.getReceipt().getId()).isEqualTo(receiptId);
            assertThat(r.getQuantity()).isEqualTo(3);
        });
        assertThat(stockMovementRepository.findAll()).isEmpty();
        assertThat(purchaseReceiptRepository.findAll()).singleElement()
                .satisfies(rc -> assertThat(rc.getCard()).isNull());

        String today = LocalDate.now().toString();
        mockMvc.perform(get(PATH).param("from", today).param("to", today)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payerSellerName").value("영수증셀러"))
                .andExpect(jsonPath("$.data[0].firstProductName").value("영수증양말"))
                .andExpect(jsonPath("$.data[0].productCount").value(1))
                .andExpect(jsonPath("$.data[0].totalAmount").value(9000))
                .andExpect(jsonPath("$.data[0].cardAlias").doesNotExist());

        mockMvc.perform(get(PATH + "/" + receiptId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photoUrl").value("https://bucket/receipts/r.png"))
                .andExpect(jsonPath("$.data.lines[0].productName").value("영수증양말"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(3));

        mockMvc.perform(get("/api/admin/purchase-list/purchases").param("productId", String.valueOf(productId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].receiptId").value(receiptId));
    }

    /** D76: no photo part → 400 with the screen text (not the catch-all 500). */
    @Test
    void testCreateWithoutPhotoIsRejected() throws Exception {
        mockMvc.perform(multipart(PATH).file(requestPart(null))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영수증 사진을 올리세요."));
    }

    /** D97: quantity below 1 → 400 (validation on the JSON part). */
    @Test
    void testCreateWithZeroQuantityIsRejected() throws Exception {
        String json = "{\"purchasedOn\":\"" + LocalDate.now() + "\",\"payerSellerId\":" + sellerId
                + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":0}]}";
        mockMvc.perform(multipart(PATH).file(photo())
                        .file(new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    /** D100: the header edit moves every line to the new payer seller and day; USER → 403. */
    @Test
    void testUpdateReceiptMovesLinesToNewSeller() throws Exception {
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/png")))
                .willReturn("https://bucket/receipts/r.png");
        String created = mockMvc.perform(multipart(PATH).file(photo()).file(requestPart(null))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(created).get("data").get("receiptId").asLong();
        Long otherSellerId = sellerRepository.save(Seller.builder()
                .sellerName("고친셀러").businessRegistration("444-55-66666").build()).getId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String body = "{\"purchasedOn\":\"" + yesterday + "\",\"payerSellerId\":" + otherSellerId + "}";

        mockMvc.perform(put(PATH + "/" + receiptId).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH + "/" + receiptId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payerSellerName").value("고친셀러"))
                .andExpect(jsonPath("$.data.purchasedOn").value(yesterday.toString()))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(3));

        purchaseRecordRepository.flush();
        assertThat(purchaseRecordRepository.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getSeller().getId()).isEqualTo(otherSellerId);
            assertThat(r.getPurchasedOn()).isEqualTo(yesterday);
        });
    }
}
