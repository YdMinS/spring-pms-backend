package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
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
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    // ==================== gitPhase 3 Iteration 1: testCreateProduct_Success_Admin_201Created ====================

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

    // ==================== Phase 3 (Integration) Cycle 1: testGetProduct_Success_200OK ====================

    @Test
    @DisplayName("Should get product by ID - HTTP 200 OK")
    public void testGetProduct_Success_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(savedProduct.getId().intValue()))
                .andExpect(jsonPath("$.data.brand").value("Samsung"));
    }

    // ==================== Phase 3 (Integration) Cycle 2: testGetProduct_ResponseStructure ====================

    @Test
    @DisplayName("Should return complete product response structure")
    public void testGetProduct_ResponseStructure() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.brand").exists())
                .andExpect(jsonPath("$.data.productName").exists())
                .andExpect(jsonPath("$.data.price").exists())
                .andExpect(jsonPath("$.data.active").exists())
                .andExpect(jsonPath("$.data.createdDate").exists())
                .andExpect(jsonPath("$.data.modifiedDate").exists());
    }

    // ==================== Phase 3 (Integration) Cycle 3: testGetProduct_MultipleProducts_CorrectOne ====================

    @Test
    @DisplayName("Should return correct product when multiple exist")
    public void testGetProduct_MultipleProducts_CorrectOne() throws Exception {
        // Given
        Product product1 = productRepository.save(ProductTestFixture.createProduct(null));
        Product product2 = productRepository.save(ProductTestFixture.createLaptopProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + product2.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product2.getId().intValue()))
                .andExpect(jsonPath("$.data.productName").value("XPS 15"));
    }

    // ==================== Phase 3 (Integration) Cycle 4: testGetProduct_NotFound_404 ====================

    @Test
    @DisplayName("Should return 404 when product not found")
    public void testGetProduct_NotFound_404() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/products/999")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3 (Integration) Cycle 5: testGetProduct_InactiveProduct_404 ====================

    @Test
    @DisplayName("Should return 404 for inactive (soft deleted) product")
    public void testGetProduct_InactiveProduct_404() throws Exception {
        // Given
        Product inactiveProduct = productRepository.save(ProductTestFixture.createInactiveProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + inactiveProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ==================== Phase 3 (Integration) Cycle 6: testGetAllProducts_Success_200OK ====================

    @Test
    @DisplayName("Should list all products - HTTP 200 OK")
    public void testGetAllProducts_Success_200OK() throws Exception {
        // Given
        productRepository.save(ProductTestFixture.createProduct(null));
        productRepository.save(ProductTestFixture.createLaptopProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    // ==================== Phase 3 (Integration) Cycle 7: testGetAllProducts_PaginationInfo ====================

    @Test
    @DisplayName("Should include pagination metadata in response")
    public void testGetAllProducts_PaginationInfo() throws Exception {
        // Given
        productRepository.save(ProductTestFixture.createProduct(null));
        productRepository.save(ProductTestFixture.createLaptopProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").exists())
                .andExpect(jsonPath("$.data.totalPages").exists())
                .andExpect(jsonPath("$.data.number").exists())
                .andExpect(jsonPath("$.data.size").exists());
    }

    // ==================== Phase 3 (Integration) Cycle 8: testGetAllProducts_DefaultPageSize ====================

    @Test
    @DisplayName("Should use default page size when not provided")
    public void testGetAllProducts_DefaultPageSize() throws Exception {
        // Given
        for (int i = 0; i < 25; i++) {
            productRepository.save(ProductTestFixture.createProduct(null));
        }

        // When & Then
        mockMvc.perform(get("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content.length()").value(20));
    }

    // ==================== Phase 3 (Integration) Cycle 9: testGetAllProducts_SearchByKeyword ====================

    @Test
    @DisplayName("Should search products by keyword")
    public void testGetAllProducts_SearchByKeyword() throws Exception {
        // Given
        productRepository.save(ProductTestFixture.createProduct(null)); // Samsung
        productRepository.save(ProductTestFixture.createLaptopProduct(null)); // Laptop

        // When & Then
        mockMvc.perform(get("/api/products?search=Samsung")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].brand").value("Samsung"));
    }

    // ==================== Phase 3 (Integration) Cycle 10: testGetAllProducts_SearchEmptyResult ====================

    @Test
    @DisplayName("Should return empty result when search finds nothing")
    public void testGetAllProducts_SearchEmptyResult() throws Exception {
        // Given
        productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products?search=NonExistent")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    // ==================== Phase 3 (Integration) Cycle 11: testGetAllProducts_OnlyActiveProducts ====================

    @Test
    @DisplayName("Should only return active products")
    public void testGetAllProducts_OnlyActiveProducts() throws Exception {
        // Given
        productRepository.save(ProductTestFixture.createProduct(null)); // active
        productRepository.save(ProductTestFixture.createInactiveProduct(null)); // inactive

        // When & Then
        mockMvc.perform(get("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].active").value(true));
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_Success_Admin_200OK ====================

    @Test
    @DisplayName("Should update product with admin token - HTTP 200 OK")
    public void testUpdateProduct_Success_Admin_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(savedProduct.getId().intValue()));
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_PartialUpdate ====================

    @Test
    @DisplayName("Should update only specified fields")
    public void testUpdateProduct_PartialUpdate() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest partialUpdate = UpdateProductRequest.builder()
                .brand(java.util.Optional.of("NewBrand"))
                .build();
        String requestBody = objectMapper.writeValueAsString(partialUpdate);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.brand").value("NewBrand"));
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_UserToken_403Forbidden ====================

    @Test
    @DisplayName("Should return 403 Forbidden when user (not admin) tries to update")
    public void testUpdateProduct_UserToken_403Forbidden() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_NoToken_401Unauthorized ====================

    @Test
    @DisplayName("Should return 401 Unauthorized when no token provided")
    public void testUpdateProduct_NoToken_401Unauthorized() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_InvalidPrice_400BadRequest ====================

    @Test
    @DisplayName("Should return 400 Bad Request when price is invalid")
    public void testUpdateProduct_InvalidPrice_400BadRequest() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest invalidPriceRequest = UpdateProductRequest.builder()
                .price(java.util.Optional.of(java.math.BigDecimal.ZERO))
                .build();
        String requestBody = objectMapper.writeValueAsString(invalidPriceRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_InvalidUnit_400BadRequest ====================

    @Test
    @DisplayName("Should return 400 Bad Request when unit is invalid")
    public void testUpdateProduct_InvalidUnit_400BadRequest() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest invalidUnitRequest = UpdateProductRequest.builder()
                .unit(java.util.Optional.of("INVALID"))
                .build();
        String requestBody = objectMapper.writeValueAsString(invalidUnitRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_NotFound_404 ====================

    @Test
    @DisplayName("Should return 404 Not Found when product doesn't exist")
    public void testUpdateProduct_NotFound_404() throws Exception {
        // Given
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/999")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_InactiveProduct_404 ====================

    @Test
    @DisplayName("Should return 404 when updating inactive (soft deleted) product")
    public void testUpdateProduct_InactiveProduct_404() throws Exception {
        // Given
        Product inactiveProduct = productRepository.save(ProductTestFixture.createInactiveProduct(null));
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + inactiveProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isNotFound());
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_ResponseStructure ====================

    @Test
    @DisplayName("Should return complete updated product response structure")
    public void testUpdateProduct_ResponseStructure() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        UpdateProductRequest updateRequest = ProductTestFixture.createUpdateRequest();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.brand").exists())
                .andExpect(jsonPath("$.data.modifiedDate").exists())
                .andExpect(jsonPath("$.data.createdDate").exists());
    }

    // ==================== Phase 2-3 Integration (PATCH): testUpdateProduct_UpdatedAtTimestamp ====================

    @Test
    @DisplayName("Should update modifiedDate timestamp on update")
    public void testUpdateProduct_UpdatedAtTimestamp() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        java.time.LocalDateTime originalModifiedDate = savedProduct.getUpdatedAt();

        UpdateProductRequest updateRequest = UpdateProductRequest.builder()
                .brand(java.util.Optional.of("UpdatedBrand"))
                .build();
        String requestBody = objectMapper.writeValueAsString(updateRequest);

        // When & Then
        mockMvc.perform(patch("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modifiedDate").exists());
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_Success_Admin_200OK ====================

    @Test
    @DisplayName("Should delete product with admin token - HTTP 200 OK")
    public void testDeleteProduct_Success_Admin_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_UserToken_403Forbidden ====================

    @Test
    @DisplayName("Should return 403 Forbidden when user (not admin) tries to delete")
    public void testDeleteProduct_UserToken_403Forbidden() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_NoToken_401Unauthorized ====================

    @Test
    @DisplayName("Should return 401 Unauthorized when no token provided")
    public void testDeleteProduct_NoToken_401Unauthorized() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_NotFound_404 ====================

    @Test
    @DisplayName("Should return 404 when product doesn't exist")
    public void testDeleteProduct_NotFound_404() throws Exception {
        // Given
        Long nonexistentId = 999L;

        // When & Then
        mockMvc.perform(delete("/api/products/" + nonexistentId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_InactiveProduct_404 ====================

    @Test
    @DisplayName("Should return 404 when trying to delete already soft deleted product")
    public void testDeleteProduct_InactiveProduct_404() throws Exception {
        // Given
        Product inactiveProduct = ProductTestFixture.createInactiveProduct(null);
        Product savedProduct = productRepository.save(inactiveProduct);

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 2-4 Integration (DELETE): testDeleteProduct_ResponseStructure ====================

    @Test
    @DisplayName("Should return proper response structure on successful delete")
    public void testDeleteProduct_ResponseStructure() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_Success_200OK ====================

    @Test
    @DisplayName("Should upload image successfully with ADMIN token - HTTP 200 OK")
    public void testUploadImage_Success_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile imageFile = ProductTestFixture.createMockImageFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(imageFile)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_NoToken_401Unauthorized ====================

    @Test
    @DisplayName("Should return 401 Unauthorized when uploading image without token")
    public void testUploadImage_NoToken_401Unauthorized() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile imageFile = ProductTestFixture.createMockImageFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(imageFile))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_UserToken_403Forbidden ====================

    @Test
    @DisplayName("Should return 403 Forbidden when uploading image with USER token")
    public void testUploadImage_UserToken_403Forbidden() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile imageFile = ProductTestFixture.createMockImageFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(imageFile)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_InvalidFileType_400BadRequest ====================

    @Test
    @DisplayName("Should return 400 Bad Request when uploading invalid file type")
    public void testUploadImage_InvalidFileType_400BadRequest() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile invalidFile = ProductTestFixture.createInvalidTypeFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(invalidFile)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_FileSizeExceeded_400BadRequest ====================

    @Test
    @DisplayName("Should return 400 Bad Request when file size exceeds 20MB")
    public void testUploadImage_FileSizeExceeded_400BadRequest() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile oversizedFile = ProductTestFixture.createOversizedFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(oversizedFile)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (PUT /image): testUploadImage_InvalidExtension_400BadRequest ====================

    @Test
    @DisplayName("Should return 400 Bad Request when file has invalid extension")
    public void testUploadImage_InvalidExtension_400BadRequest() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile invalidExtensionFile = ProductTestFixture.createInvalidExtensionFile();

        // When & Then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(invalidExtensionFile)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (GET /image): testGetImage_Success_200OK ====================

    @Test
    @DisplayName("Should retrieve image successfully - HTTP 200 OK")
    public void testGetImage_Success_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile imageFile = ProductTestFixture.createMockImageFile();
        // Upload image first
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(imageFile)
                .header("Authorization", "Bearer " + adminToken));

        // When & Then
        mockMvc.perform(get("/api/products/" + savedProduct.getId() + "/image")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ==================== Phase 3-1 Integration (GET /image): testGetImage_NotFound_404 ====================

    @Test
    @DisplayName("Should return 404 Not Found when image doesn't exist")
    public void testGetImage_NotFound_404() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + savedProduct.getId() + "/image")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ==================== Phase 3-1 Integration (GET /image): testGetImage_NoImage_204NoContent ====================

    @Test
    @DisplayName("Should return 204 No Content when product has no image")
    public void testGetImage_NoImage_204NoContent() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(get("/api/products/" + savedProduct.getId() + "/image")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ==================== Phase 3-1 Integration (DELETE /image): testDeleteImage_Success_200OK ====================

    @Test
    @DisplayName("Should delete image successfully with ADMIN token - HTTP 200 OK")
    public void testDeleteImage_Success_200OK() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));
        MockMultipartFile imageFile = ProductTestFixture.createMockImageFile();
        // Upload image first
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/" + savedProduct.getId() + "/image")
                .file(imageFile)
                .header("Authorization", "Bearer " + adminToken));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId() + "/image")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ==================== Phase 3-1 Integration (DELETE /image): testDeleteImage_NoToken_401Unauthorized ====================

    @Test
    @DisplayName("Should return 401 Unauthorized when deleting image without token")
    public void testDeleteImage_NoToken_401Unauthorized() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId() + "/image"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (DELETE /image): testDeleteImage_UserToken_403Forbidden ====================

    @Test
    @DisplayName("Should return 403 Forbidden when deleting image with USER token")
    public void testDeleteImage_UserToken_403Forbidden() throws Exception {
        // Given
        Product savedProduct = productRepository.save(ProductTestFixture.createProduct(null));

        // When & Then
        mockMvc.perform(delete("/api/products/" + savedProduct.getId() + "/image")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ==================== Phase 3-1 Integration (DELETE /image): testDeleteImage_NotFound_404 ====================

    @Test
    @DisplayName("Should return 404 Not Found when trying to delete non-existent product")
    public void testDeleteImage_NotFound_404() throws Exception {
        // Given
        Long nonexistentId = 999L;

        // When & Then
        mockMvc.perform(delete("/api/products/" + nonexistentId + "/image")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
