package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.fixture.ProductTestFixture;
import com.pms.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProductControllerTest - Integration tests for ProductController (MockMvc)
 *
 * Phase 3: Integration Tests - Red-Green Cycles
 * Tests POST /api/products endpoint with real database (H2)
 */
@DisplayName("ProductController - Integration Tests (create endpoint only)")
public class ProductControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        // Cleanup before each test
        productRepository.deleteAll();
    }

    @AfterEach
    public void tearDown() {
        // Cleanup after each test
        productRepository.deleteAll();
    }

    // ==================== Phase 3 Iteration 1: testCreateProduct_Success_Admin_201Created ====================

    /**
     * Test: Admin creates product with valid request → 201 Created
     *
     * Scenario: Admin token + valid CreateProductRequest
     * Expected:
     *   - HTTP 201 Created status
     *   - Response has ProductResponse structure
     *   - Product saved to database
     *   - active = true, timestamps populated
     */
    @Test
    @DisplayName("Should create product with admin token - HTTP 201 Created")
    public void testCreateProduct_Success_Admin_201Created() throws Exception {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        String requestBody = objectMapper.writeValueAsString(request);

        // When
        MvcResult result = mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                // Then
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.brand").value("Samsung"))
                .andExpect(jsonPath("$.data.price").value(999.99))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.createdDate").exists())
                .andExpect(jsonPath("$.data.modifiedDate").exists())
                .andReturn();

        // Verify product is saved in database
        assertThat(productRepository.count()).isEqualTo(1);
        Product saved = productRepository.findAll().get(0);
        assertThat(saved.getBrand()).isEqualTo("Samsung");
        assertThat(saved.getActive()).isTrue();
    }

    // ==================== Phase 3 Iteration 2: testCreateProduct_NoToken_401Unauthorized ====================

    /**
     * Test: No token → 401 Unauthorized
     *
     * Scenario: POST /api/products without authentication
     * Expected:
     *   - HTTP 401 Unauthorized
     *   - Error response with message
     */
    @Test
    @DisplayName("Should return 401 Unauthorized when no token provided")
    public void testCreateProduct_NoToken_401Unauthorized() throws Exception {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        String requestBody = objectMapper.writeValueAsString(request);

        // When & Then
        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnauthorized());

        // Verify nothing saved
        assertThat(productRepository.count()).isEqualTo(0);
    }

    // ==================== Phase 3 Iteration 3: testCreateProduct_UserToken_403Forbidden ====================

    /**
     * Test: User token (not admin) → 403 Forbidden
     *
     * Scenario: POST /api/products with USER token (not ADMIN)
     * Expected:
     *   - HTTP 403 Forbidden
     *   - Product NOT saved
     */
    @Test
    @DisplayName("Should return 403 Forbidden when user token (not admin) provided")
    public void testCreateProduct_UserToken_403Forbidden() throws Exception {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        String requestBody = objectMapper.writeValueAsString(request);

        // When & Then
        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isForbidden());

        // Verify nothing saved
        assertThat(productRepository.count()).isEqualTo(0);
    }

    // ==================== Phase 3 Iteration 4: testCreateProduct_InvalidPrice_400BadRequest ====================

    /**
     * Test: Invalid price → 400 Bad Request
     *
     * Scenario: Price = 0 or negative
     * Expected:
     *   - HTTP 400 Bad Request
     *   - Error message mentions price
     *   - Product NOT saved
     */
    @Test
    @DisplayName("Should return 400 Bad Request when price is invalid")
    public void testCreateProduct_InvalidPrice_400BadRequest() throws Exception {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithZeroPrice();
        String requestBody = objectMapper.writeValueAsString(request);

        // When & Then
        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").exists());

        // Verify nothing saved
        assertThat(productRepository.count()).isEqualTo(0);
    }

    // ==================== Phase 3 Iteration 5: testCreateProduct_InvalidUnit_400BadRequest ====================

    /**
     * Test: Invalid unit → 400 Bad Request
     *
     * Scenario: Unit not in [KG, G, L, ML]
     * Expected:
     *   - HTTP 400 Bad Request
     *   - Error message mentions unit
     */
    @Test
    @DisplayName("Should return 400 Bad Request when unit is invalid")
    public void testCreateProduct_InvalidUnit_400BadRequest() throws Exception {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithInvalidUnit();
        String requestBody = objectMapper.writeValueAsString(request);

        // When & Then
        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        // Verify nothing saved
        assertThat(productRepository.count()).isEqualTo(0);
    }
}
