package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import com.pms.service.ProductService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The merge is one transaction: a failure leaves both products exactly as they were (PLAN D6).
 *
 * <p>🔴 <b>Deliberately NOT {@code BaseIntegrationTest}</b>. That base wraps every test in a rolling-back
 * transaction, which the merge would simply join — its writes would stay visible in the same persistence
 * context and a "rollback" assertion would prove nothing. Here each operation runs in its own transaction,
 * so the merge really rolls back and the assertions read what survived. Same reason
 * {@code ChannelAddControllerTest} is non-transactional; seeds are committed and cleaned up by hand.</p>
 *
 * <p>⚠️ {@link TenantContext} has to be (re)set after the login request: the JWT filter clears it in its
 * {@code finally}, and without a tenant every {@code @TenantId} insert would be stamped {@code NO_TENANT}
 * and the tenant-1 request could not see the seeds.</p>
 *
 * <p>The failure is injected at the very last step (the guarded soft delete) so every earlier step —
 * reassignments, gallery re-numbering, the field overwrite — has already happened.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductMergeRollbackIntegrationTest {

    private static final String ADMIN_EMAIL = "merge-admin@test.com";
    private static final String ADMIN_PASSWORD = "testpass123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private BCryptPasswordEncoder passwordEncoder;
    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private ProductImageRepository productImageRepository;

    @SpyBean private ProductService productService;

    private String adminToken;
    private Long targetId;
    private Long sourceId;

    @BeforeEach
    void seed() throws Exception {
        TenantContext.set(1L);
        userRepository.save(User.builder()
                .email(ADMIN_EMAIL).password(passwordEncoder.encode(ADMIN_PASSWORD))
                .name("Merge Admin").role(Role.ADMIN).build());
        String login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        adminToken = objectMapper.readTree(login).get("data").get("token").asText();

        TenantContext.set(1L);      // the JWT filter cleared it while serving the login above
        targetId = productRepository.save(Product.builder()
                .productName("남길 물품").brand("남길브랜드").active(true).build()).getId();
        Product source = productRepository.save(Product.builder()
                .productName("버릴 물품").brand("버릴브랜드").active(true).build());
        sourceId = source.getId();
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        purchaseRecordRepository.save(PurchaseRecord.builder()
                .product(source).seller(seller).purchasedOn(LocalDate.of(2026, 9, 1)).quantity(1)
                .totalAmount(new BigDecimal("10000")).unitPrice(new BigDecimal("10000"))
                .reflectToBasePrice(false).build());
        productImageRepository.save(ProductImage.builder()
                .product(source).sortOrder(0).imageUrl("https://cdn/s0.jpg").build());
    }

    @AfterEach
    void cleanup() {
        TenantContext.set(1L);      // deleteAll reads through the tenant filter
        productImageRepository.deleteAll();
        purchaseRecordRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        // ⚠️ delete(entity), not the derived deleteByEmail: a derived delete needs an ambient transaction
        // and this class has none.
        userRepository.findByEmail(ADMIN_EMAIL).ifPresent(userRepository::delete);
        TenantContext.clear();
    }

    @Test
    void testMergeRollsBackOnFailure() throws Exception {
        willThrow(new IllegalStateException("simulated failure after the history moved"))
                .given(productService).deleteProduct(any());

        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetProductId": %d,
                                  "sourceProductId": %d,
                                  "fields": {"productName": "덮어쓴 이름", "brand": "덮어쓴 브랜드"},
                                  "transfer": {
                                    "purchaseRecords": true, "stockMovements": true, "shipmentItems": true,
                                    "images": true, "shoppingListItems": true, "priceChangeLogs": true,
                                    "appendMemo": true
                                  }
                                }
                                """.formatted(targetId, sourceId)))
                .andExpect(status().isInternalServerError());

        // 🔴 Without this the test would also pass if the merge had failed EARLIER (e.g. a validation) —
        // the assertions below cannot tell the two apart on their own.
        verify(productService).deleteProduct(sourceId);

        TenantContext.set(1L);
        // History stayed on the source, the target kept its own values, and the source is still active.
        assertThat(purchaseRecordRepository.countByProductId(sourceId)).isEqualTo(1);
        assertThat(purchaseRecordRepository.countByProductId(targetId)).isZero();
        assertThat(productImageRepository.countByProductId(sourceId)).isEqualTo(1);
        assertThat(productImageRepository.countByProductId(targetId)).isZero();

        Product reloadedTarget = productRepository.findScopedById(targetId).orElseThrow();
        assertThat(reloadedTarget.getProductName()).isEqualTo("남길 물품");
        assertThat(reloadedTarget.getBrand()).isEqualTo("남길브랜드");
        assertThat(reloadedTarget.getDescription()).isNull();
        assertThat(productRepository.findScopedById(sourceId).orElseThrow().getActive()).isTrue();
    }
}
