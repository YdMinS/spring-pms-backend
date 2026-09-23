package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 The reported bug, end to end: delete one of two duplicate products, then give its barcode to the
 * survivor.
 *
 * <p>A mocked repository cannot prove this. The deleted row keeps existing and
 * {@code uq_products_tenant_barcode} (changeset 098) counts it, so only a real round-trip shows that the
 * application guard AND the database key both let the reuse through. Before the fix the second create
 * was rejected — by the guard with a 409, and if the guard had merely been taught to skip inactive rows,
 * by the key with an unreadable 500.</p>
 */
class ProductBarcodeReleaseIntegrationTest extends BaseIntegrationTest {

    private static final String BARCODE = "2087686005954";

    @Autowired
    private ProductRepository productRepository;

    private long createProduct(String name, String barcode) throws Exception {
        String response = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName": "%s", "barcodeId": "%s"}
                                """.formatted(name, barcode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("id").asLong();
    }

    @Test
    void testBarcodeOfADeletedProductCanBeGivenToAnother() throws Exception {
        long duplicateId = createProduct("아몬드 초코볼(중복)", BARCODE);

        mockMvc.perform(delete("/api/products/" + duplicateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // The hidden row no longer owns the code — that is what frees it
        Product deleted = productRepository.findById(duplicateId).orElseThrow();
        assertThat(deleted.getActive()).isFalse();
        assertThat(deleted.getBarcodeId()).isNull();

        // ...so the surviving product can take it over
        long survivorId = createProduct("아몬드 초코볼", BARCODE);
        assertThat(productRepository.findById(survivorId).orElseThrow().getBarcodeId()).isEqualTo(BARCODE);
    }

    /**
     * ⚠️ Releasing on delete must not weaken the rule for products that are still alive: two active
     * products may never claim the same code.
     */
    @Test
    void testDuplicateAmongActiveProductsIsStillRejected() throws Exception {
        long duplicateId = createProduct("아몬드 초코볼(중복)", BARCODE);

        mockMvc.perform(delete("/api/products/" + duplicateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        long survivorId = createProduct("아몬드 초코볼", BARCODE);

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName": "어쏘티드 초코볼", "barcodeId": "%s"}
                                """.formatted(BARCODE)))
                .andExpect(status().isConflict())
                // the message has to name the row that holds the code, or nobody can find the cause
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(String.valueOf(survivorId))));
    }
}
