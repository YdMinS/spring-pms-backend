package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.repository.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authority on {@code POST /api/products/merge} (FEATURE_2609_69 / B).
 *
 * <p>⚠️ {@code ProductController} carries no {@code @PreAuthorize} — the ADMIN rule lives in
 * {@code config/SecurityConfig}, and the {@code POST /api/products} matcher there is an <b>exact</b> match.
 * Without the dedicated {@code /api/products/merge} matcher this endpoint falls through to
 * {@code anyRequest().authenticated()} and the 403 below fails.</p>
 */
class ProductMergeControllerTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;

    private String body;

    @BeforeEach
    void seed() {
        Long targetId = productRepository.save(Product.builder()
                .productName("남길 물품").active(true).build()).getId();
        Long sourceId = productRepository.save(Product.builder()
                .productName("버릴 물품").active(true).build()).getId();
        body = """
                {
                  "targetProductId": %d,
                  "sourceProductId": %d,
                  "fields": {},
                  "transfer": {
                    "purchaseRecords": true, "stockMovements": true, "shipmentItems": true,
                    "images": true, "shoppingListItems": true, "priceChangeLogs": true,
                    "appendMemo": true
                  }
                }
                """.formatted(targetId, sourceId);
    }

    @Test
    void testMergeWithoutToken() throws Exception {
        mockMvc.perform(post("/api/products/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testMergeWithNonAdminRole() throws Exception {
        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void testMergeWithAdminRole() throws Exception {
        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moved").exists())
                .andExpect(jsonPath("$.data.snapshotFileName").exists());
    }
}
